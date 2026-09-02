package org.example.xyjaicontext.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.xyjaicontext.memory.RedisChatMemory;
import org.example.xyjaicontext.memory.UserContext;
import org.example.xyjaicontext.service.ModelService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGraphNodes {

    private final ModelService modelService;
    private final VectorStore vectorStore;
    private final RedisChatMemory chatMemory;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agent.context.max-chars:12000}")
    private int maxContextChars;

    @Value("${agent.context.recent-message-count:8}")
    private int recentMessageCount;

    @Value("${agent.rag.top-k:6}")
    private int ragTopK;

    @Value("${agent.rag.similarity-threshold:0.72}")
    private double similarityThreshold;

    @Value("${agent.document.result-ttl-hours:168}")
    private long documentResultTtlHours;

    public Map<String, Object> loadMemory(OverAllState state) {
        String conversationId = value(state, GraphStateKeys.CONVERSATION_ID, "");
        String username = value(state, GraphStateKeys.USERNAME, "anonymous");
        List<String> recent = new ArrayList<>();
        if (!conversationId.isBlank()) {
            UserContext.setUsername(username);
            try {
                List<Message> messages = chatMemory.get(conversationId);
                int start = Math.max(0, messages.size() - Math.max(1, recentMessageCount));
                for (Message message : messages.subList(start, messages.size())) {
                    String text = message.getText();
                    if (text != null && !text.isBlank()) {
                        recent.add(message.getMessageType().name() + ": " + text);
                    }
                }
            } finally {
                UserContext.clear();
            }
        }
        return Map.of(GraphStateKeys.RECENT_MESSAGES, recent,
                GraphStateKeys.CONVERSATION_SUMMARY, "");
    }

    public Map<String, Object> routeIntent(OverAllState state) {
        boolean useRag = Boolean.TRUE.equals(state.value(GraphStateKeys.USE_RAG, false));
        String question = value(state, GraphStateKeys.QUESTION, "");
        boolean documentQuestion = question.matches(".*(文档|资料|文件|引用|根据|手册|说明书|pdf|word|检索).*");
        String route = useRag || documentQuestion ? "RAG" : "NORMAL";
        // Clear per-request retrieval state when a conversation thread is
        // resumed from Redis; otherwise a normal chat could reuse old RAG data.
        return Map.of(GraphStateKeys.ROUTE, route,
                GraphStateKeys.REWRITTEN_QUERY, "",
                GraphStateKeys.RETRIEVED_CHUNKS, List.of(),
                GraphStateKeys.COMPRESSED_CONTEXT, "");
    }

    public String routeAfterIntent(OverAllState state) {
        return value(state, GraphStateKeys.ROUTE, "NORMAL");
    }

    public Map<String, Object> rewriteQuery(OverAllState state) {
        String question = value(state, GraphStateKeys.QUESTION, "");
        String history = joinStrings(state, GraphStateKeys.RECENT_MESSAGES);
        String rewritten = question;
        if (!history.isBlank()) {
            rewritten = callModel(
                    "你是检索查询改写 Agent。结合历史对话把用户问题改写成一个独立、简洁的中文检索查询，只返回查询本身。",
                    "历史对话:\n" + history + "\n当前问题:\n" + question);
        }
        return Map.of(GraphStateKeys.REWRITTEN_QUERY, rewritten.isBlank() ? question : rewritten);
    }

    public Map<String, Object> retrieveChroma(OverAllState state) {
        String query = value(state, GraphStateKeys.REWRITTEN_QUERY,
                value(state, GraphStateKeys.QUESTION, ""));
        String username = value(state, GraphStateKeys.USERNAME, "");
        List<Map<String, Object>> chunks = new ArrayList<>();
        try {
            List<Document> docs;
            try {
                SearchRequest.Builder request = SearchRequest.builder()
                        .query(query)
                        .topK(Math.max(1, ragTopK))
                        .similarityThreshold(similarityThreshold);
                if (!username.isBlank()) {
                    request.filterExpression(new FilterExpressionBuilder().eq("username", username).build());
                }
                docs = vectorStore.similaritySearch(request.build());
            } catch (Exception unsupportedFilter) {
                // Older Chroma deployments may not understand Spring AI filter
                // expressions; retrieve a wider set and enforce the tenant check locally.
                docs = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query)
                        .topK(Math.max(1, ragTopK) * 3)
                        .similarityThreshold(similarityThreshold)
                        .build());
            }
            if (docs != null) {
                for (Document doc : docs) {
                    Object owner = doc.getMetadata().get("username");
                    if (username.isBlank() || !username.equals(String.valueOf(owner))) {
                        continue;
                    }
                    Map<String, Object> chunk = new LinkedHashMap<>();
                    chunk.put("text", doc.getText());
                    chunk.put("metadata", new LinkedHashMap<>(doc.getMetadata()));
                    chunks.add(chunk);
                }
            }
        } catch (Exception e) {
            log.warn("Chroma retrieval failed, continuing with an empty context: {}", e.getMessage());
        }
        return Map.of(GraphStateKeys.RETRIEVED_CHUNKS, chunks);
    }

    public Map<String, Object> compressContext(OverAllState state) {
        String retrieved = retrievedText(state);
        String history = joinStrings(state, GraphStateKeys.RECENT_MESSAGES);
        String combined = (history.isBlank() ? "" : "历史对话:\n" + history + "\n")
                + (retrieved.isBlank() ? "" : "检索资料:\n" + retrieved);
        if (combined.length() > maxContextChars) {
            combined = callModel(
                    "你是上下文压缩 Agent。保留与当前问题直接相关的事实、数字、约束和来源标识，删除重复内容。只输出压缩后的事实上下文。",
                    "当前问题:\n" + value(state, GraphStateKeys.QUESTION, "")
                            + "\n待压缩上下文:\n" + combined.substring(0, Math.min(combined.length(), maxContextChars * 2)));
        }
        if (combined.length() > maxContextChars) {
            combined = combined.substring(0, maxContextChars);
        }
        return Map.of(GraphStateKeys.COMPRESSED_CONTEXT, combined);
    }

    public Map<String, Object> generateAnswer(OverAllState state) {
        String route = value(state, GraphStateKeys.ROUTE, "NORMAL");
        String question = value(state, GraphStateKeys.QUESTION, "");
        String context = value(state, GraphStateKeys.COMPRESSED_CONTEXT, "");
        String system = "你是一个专业、谨慎的 AI 助手。";
        if ("RAG".equals(route)) {
            system += "只能依据提供的资料回答；资料不足时明确说明，不要编造。回答中保留可识别的来源信息。";
        }
        String prompt = (context.isBlank() ? "" : "上下文:\n" + context + "\n\n") + "问题:\n" + question;
        String answer = callModel(system, prompt, true);
        return Map.of(GraphStateKeys.DRAFT_ANSWER, answer, GraphStateKeys.FINAL_ANSWER, answer);
    }

    public Map<String, Object> verifyAnswer(OverAllState state) {
        String answer = value(state, GraphStateKeys.FINAL_ANSWER, "");
        int retries = state.value(GraphStateKeys.RETRY_COUNT, 0);
        boolean verified = !answer.isBlank();
        return Map.of(GraphStateKeys.VERIFIED, verified,
                GraphStateKeys.RETRY_COUNT, verified ? retries : retries + 1);
    }

    public String routeAfterVerification(OverAllState state) {
        boolean verified = Boolean.TRUE.equals(state.value(GraphStateKeys.VERIFIED, false));
        int retries = state.value(GraphStateKeys.RETRY_COUNT, 0);
        return !verified && retries < 2 ? "RETRY" : "DONE";
    }

    public Map<String, Object> persistMemory(OverAllState state) {
        String conversationId = value(state, GraphStateKeys.CONVERSATION_ID, "");
        String username = value(state, GraphStateKeys.USERNAME, "anonymous");
        String question = value(state, GraphStateKeys.QUESTION, "");
        String answer = value(state, GraphStateKeys.FINAL_ANSWER, "");
        if (!conversationId.isBlank() && !question.isBlank() && !answer.isBlank()) {
            UserContext.setUsername(username);
            try {
                chatMemory.add(conversationId, List.of(new UserMessage(question), new AssistantMessage(answer)));
            } finally {
                UserContext.clear();
            }
        }
        return Map.of();
    }

    public Map<String, Object> parseDocument(OverAllState state) throws Exception {
        String taskId = value(state, GraphStateKeys.TASK_ID, "");
        markDocumentStatus(taskId, "PARSING", AgentNodeNames.PARSE_DOCUMENT);
        Object raw = state.value(GraphStateKeys.FILE_CONTENT).orElse(null);
        if (!(raw instanceof byte[] content) || content.length == 0) {
            throw new IllegalArgumentException("文档内容为空");
        }
        TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(content));
        List<Document> parsed = reader.get();
        List<Document> split = new TokenTextSplitter().apply(parsed);
        List<String> chunks = split.stream().map(Document::getText).filter(t -> t != null && !t.isBlank()).toList();
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档未解析出可索引文本");
        }
        return Map.of(GraphStateKeys.PARSED_TEXT,
                parsed.stream().map(Document::getText).filter(t -> t != null).toList(),
                GraphStateKeys.CHUNKS, chunks,
                GraphStateKeys.FILE_CONTENT, new byte[0]);
    }

    public Map<String, Object> summarizeDocument(OverAllState state) {
        String taskId = value(state, GraphStateKeys.TASK_ID, "");
        markDocumentStatus(taskId, "ANALYZING", AgentNodeNames.SUMMARIZE_DOCUMENT);
        String text = joinStrings(state, GraphStateKeys.PARSED_TEXT);
        String summary = normalizeJson(callModel("你是文档摘要 Agent。提取文档目的、核心结论、关键数字和限制条件。只返回 JSON 对象，字段为 purpose、conclusions、keyNumbers、constraints。",
                text.substring(0, Math.min(text.length(), maxContextChars * 3))));
        return Map.of(GraphStateKeys.DOCUMENT_SUMMARY, summary);
    }

    public Map<String, Object> extractFacts(OverAllState state) {
        String text = joinStrings(state, GraphStateKeys.PARSED_TEXT);
        String facts = normalizeJson(callModel("你是事实抽取 Agent。只抽取文档中明确出现的实体、日期、数字、规则和结论，逐条列出，不要推测。只返回 JSON 数组，每项包含 type、value、source。",
                text.substring(0, Math.min(text.length(), maxContextChars * 3))), true);
        return Map.of(GraphStateKeys.FACTS, facts);
    }

    public Map<String, Object> generateQuestions(OverAllState state) {
        String text = joinStrings(state, GraphStateKeys.PARSED_TEXT);
        String questions = normalizeJson(callModel("你是文档问答 Agent。根据文档生成 5 个有答案的高质量中文问题及简短答案。只返回 JSON 数组，每项包含 question、answer。",
                text.substring(0, Math.min(text.length(), maxContextChars * 3))), true);
        return Map.of(GraphStateKeys.QUESTIONS, questions);
    }

    public Map<String, Object> indexDocument(OverAllState state) {
        String taskId = value(state, GraphStateKeys.TASK_ID, "");
        markDocumentStatus(taskId, "INDEXING", AgentNodeNames.INDEX_DOCUMENT);
        String fileName = value(state, GraphStateKeys.FILE_NAME, "unknown");
        String username = value(state, GraphStateKeys.USERNAME, "anonymous");
        String checksum = value(state, GraphStateKeys.CHECKSUM, "");
        List<String> chunks = listValue(state, GraphStateKeys.CHUNKS);
        String checksumKey = "doc:checksum:" + username + ":" + checksum;
        String existingTask = checksum.isBlank() ? null : redisTemplate.opsForValue().get(checksumKey);
        if (existingTask != null && !existingTask.equals(taskId)) {
            String existingStatus = redisTemplate.opsForValue().get("doc:status:" + existingTask);
            boolean existingResult = Boolean.TRUE.equals(redisTemplate.hasKey("doc:result:" + existingTask));
            if (existingResult || (existingStatus != null && existingStatus.startsWith("COMPLETED"))) {
                return Map.of(GraphStateKeys.CHUNK_COUNT, 0,
                        GraphStateKeys.DUPLICATE_OF, existingTask);
            }
            // The previous task did not finish indexing; let this task claim
            // the checksum instead of suppressing a valid retry.
            redisTemplate.opsForValue().set(checksumKey, taskId, documentResultTtlHours, TimeUnit.HOURS);
        }
        if (!checksum.isBlank()) {
            redisTemplate.opsForValue().setIfAbsent(checksumKey, taskId, documentResultTtlHours, TimeUnit.HOURS);
        }
        String sourceKey = "doc:source:" + username + ":" + fileName;
        String previousTask = redisTemplate.opsForValue().get(sourceKey);
        if (previousTask != null && !previousTask.equals(taskId)) {
            try {
                vectorStore.delete(new FilterExpressionBuilder().eq("taskId", previousTask).build());
            } catch (Exception e) {
                log.debug("No previous version removed for source {}: {}", fileName, e.getMessage());
            }
        }
        redisTemplate.opsForValue().set(sourceKey, taskId, documentResultTtlHours, TimeUnit.HOURS);
        // A retry may reach this node after a partial vector write. Remove only
        // this task's vectors before adding the current complete set.
        try {
            vectorStore.delete(new FilterExpressionBuilder().eq("taskId", taskId).build());
        } catch (Exception e) {
            log.debug("No previous vectors removed for task {}: {}", taskId, e.getMessage());
        }
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document document = new Document(chunks.get(i));
            document.getMetadata().put("source", fileName);
            document.getMetadata().put("taskId", taskId);
            document.getMetadata().put("username", username);
            document.getMetadata().put("chunkId", taskId + "-" + i);
            document.getMetadata().put("checksum", checksum);
            documents.add(document);
        }
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
        return Map.of(GraphStateKeys.CHUNK_COUNT, documents.size());
    }

    public Map<String, Object> finalizeDocument(OverAllState state) {
        String taskId = value(state, GraphStateKeys.TASK_ID, "");
        markDocumentStatus(taskId, "COMPLETED", AgentNodeNames.FINALIZE_DOCUMENT);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(GraphStateKeys.TASK_ID, taskId);
        result.put(GraphStateKeys.USERNAME, value(state, GraphStateKeys.USERNAME, "anonymous"));
        result.put(GraphStateKeys.FILE_NAME, value(state, GraphStateKeys.FILE_NAME, "unknown"));
        result.put(GraphStateKeys.CHECKSUM, value(state, GraphStateKeys.CHECKSUM, ""));
        result.put(GraphStateKeys.DOCUMENT_SUMMARY, value(state, GraphStateKeys.DOCUMENT_SUMMARY, ""));
        result.put(GraphStateKeys.FACTS, value(state, GraphStateKeys.FACTS, ""));
        result.put(GraphStateKeys.QUESTIONS, value(state, GraphStateKeys.QUESTIONS, ""));
        result.put(GraphStateKeys.CHUNK_COUNT, state.value(GraphStateKeys.CHUNK_COUNT, 0));
        result.put(GraphStateKeys.DUPLICATE_OF, value(state, GraphStateKeys.DUPLICATE_OF, ""));
        result.put(GraphStateKeys.DOCUMENT_STATUS, "COMPLETED");
        result.put(GraphStateKeys.CURRENT_NODE, AgentNodeNames.FINALIZE_DOCUMENT);
        try {
            redisTemplate.opsForValue().set("doc:result:" + taskId,
                    objectMapper.writeValueAsString(result), documentResultTtlHours, TimeUnit.HOURS);
        } catch (Exception e) {
            throw new IllegalStateException("文档结果保存失败", e);
        }
        return result;
    }

    private String callModel(String system, String prompt) {
        return callModel(system, prompt, false);
    }

    private String callModel(String system, String prompt, boolean allowEmpty) {
        return modelService.call(system, prompt, allowEmpty);
    }

    private String normalizeJson(String response) {
        return normalizeJson(response, false);
    }

    private String normalizeJson(String response, boolean expectedArray) {
        String candidate = response == null ? "" : response.trim();
        if (candidate.startsWith("```") && candidate.endsWith("```")) {
            candidate = candidate.substring(3, candidate.length() - 3).trim();
            if (candidate.startsWith("json")) {
                candidate = candidate.substring(4).trim();
            }
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(candidate);
            if (node == null || node.isNull() || node.isArray() != expectedArray) {
                throw new IllegalArgumentException("模型输出为空 JSON");
            }
            return node.toString();
        } catch (Exception e) {
            try {
                if (expectedArray) {
                    return objectMapper.writeValueAsString(List.of(Map.of("raw", candidate)));
                }
                return objectMapper.writeValueAsString(Map.of("raw", candidate));
            } catch (Exception serializationError) {
                throw new IllegalStateException("结构化模型输出校验失败", serializationError);
            }
        }
    }

    private void markDocumentStatus(String taskId, String status, String node) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set("doc:status:" + taskId,
                status + "|node=" + node, 24, TimeUnit.HOURS);
    }

    private String retrievedText(OverAllState state) {
        Object raw = state.value(GraphStateKeys.RETRIEVED_CHUNKS).orElse(Collections.emptyList());
        if (!(raw instanceof List<?> list)) {
            return "";
        }
        return list.stream().map(item -> {
                    if (item instanceof Map<?, ?> map) {
                        Object text = map.get("text");
                        return text == null ? "" : String.valueOf(text);
                    }
                    return String.valueOf(item);
                })
                .collect(Collectors.joining("\n---\n"));
    }

    private String joinStrings(OverAllState state, String key) {
        return String.join("\n", listValue(state, key));
    }

    private List<String> listValue(OverAllState state, String key) {
        Object raw = state.value(key).orElse(Collections.emptyList());
        if (!(raw instanceof List<?> list)) {
            return raw == null ? List.of() : List.of(String.valueOf(raw));
        }
        return list.stream().map(String::valueOf).toList();
    }

    private String value(OverAllState state, String key, String fallback) {
        Object value = state.value(key).orElse(null);
        return value == null ? fallback : String.valueOf(value);
    }
}
