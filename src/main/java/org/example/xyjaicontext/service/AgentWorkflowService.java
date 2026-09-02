package org.example.xyjaicontext.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.xyjaicontext.agent.GraphStateKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Entry point for the chat StateGraph. The old ChatService remains available as a fallback. */
@Service
public class AgentWorkflowService {

    private final CompiledGraph chatGraph;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentWorkflowService(@Qualifier("chatCompiledGraph") CompiledGraph chatGraph,
                                StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper) {
        this.chatGraph = chatGraph;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String chat(String question, boolean useRag, String conversationId, String username) {
        return chatWithResult(question, useRag, conversationId, username).answer();
    }

    public ChatResult chatWithResult(String question, boolean useRag, String conversationId, String username) {
        String runId = UUID.randomUUID().toString();
        String owner = username == null || username.isBlank() ? "anonymous" : username;
        String threadId = owner + ":" + conversationId;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(GraphStateKeys.RUN_ID, runId);
        input.put(GraphStateKeys.QUESTION, question == null ? "" : question.trim());
        input.put(GraphStateKeys.USE_RAG, useRag);
        input.put(GraphStateKeys.CONVERSATION_ID, conversationId);
        input.put(GraphStateKeys.USERNAME, owner);
        input.put(GraphStateKeys.RETRY_COUNT, 0);

        saveRun(runId, owner, "RUNNING", Map.of(GraphStateKeys.QUESTION, input.get(GraphStateKeys.QUESTION)));
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
            OverAllState state = chatGraph.invoke(input, config)
                    .orElseThrow(() -> new IllegalStateException("聊天工作流未返回结果"));
            String answer = state.value(GraphStateKeys.FINAL_ANSWER, "");
            if (answer == null || answer.isBlank()) {
                throw new ModelCallException("模型在重试后仍返回空响应");
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put(GraphStateKeys.ROUTE, state.value(GraphStateKeys.ROUTE, "NORMAL"));
            output.put(GraphStateKeys.FINAL_ANSWER, answer);
            output.put(GraphStateKeys.RETRY_COUNT, state.value(GraphStateKeys.RETRY_COUNT, 0));
            saveRun(runId, owner, "COMPLETED", output);
            return new ChatResult(runId, answer);
        } catch (RuntimeException e) {
            saveRun(runId, owner, "FAILED", Map.of(GraphStateKeys.ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            throw e;
        }
    }

    public Map<String, Object> getRun(String runId, String username) {
        String json = redisTemplate.opsForValue().get("agent:run:" + runId);
        if (json == null) {
            return null;
        }
        try {
            Map<String, Object> run = objectMapper.readValue(json, new TypeReference<>() {});
            return username != null && username.equals(String.valueOf(run.get(GraphStateKeys.USERNAME))) ? run : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void saveRun(String runId, String username, String status, Map<String, Object> output) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put(GraphStateKeys.RUN_ID, runId);
        run.put(GraphStateKeys.USERNAME, username);
        run.put("status", status);
        run.put("updatedAt", System.currentTimeMillis());
        run.put("output", output);
        try {
            redisTemplate.opsForValue().set("agent:run:" + runId,
                    objectMapper.writeValueAsString(run), 24, java.util.concurrent.TimeUnit.HOURS);
        } catch (Exception e) {
            throw new IllegalStateException("Agent run 状态保存失败", e);
        }
    }

    public record ChatResult(String runId, String answer) {
    }
}
