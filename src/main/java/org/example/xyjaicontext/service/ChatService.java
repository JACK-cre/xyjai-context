package org.example.xyjaicontext.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.xyjaicontext.memory.RedisChatMemory;
import org.example.xyjaicontext.memory.UserContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.stream.Collectors;
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient.Builder chatClientBuilder;
    private final VectorStore vectorStore;
    private final RedisChatMemory chatMemory;
    private final ModelService modelService;
    private final AgentWorkflowService agentWorkflowService;

    @Value("${agent.graph.enabled:true}")
    private boolean graphEnabled;

    /**
     * 统一对话入口
     */
    public String chat(String question, boolean useRag, String conversationId, String username) {
        return chatWithMetadata(question, useRag, conversationId, username).answer();
    }

    public AgentWorkflowService.ChatResult chatWithMetadata(String question, boolean useRag,
                                                            String conversationId, String username) {
        if (graphEnabled) {
            try {
                return agentWorkflowService.chatWithResult(question, useRag, conversationId, username);
            } catch (ModelCallException e) {
                throw e;
            } catch (Exception e) {
                log.error("Agent StateGraph failed, falling back to legacy chat flow", e);
            }
        }
        String answer = useRag ? chatWithRag(question, conversationId, username) : chatNormal(question, conversationId, username);
        return new AgentWorkflowService.ChatResult(null, answer);
    }

    /**
     * RAG 模式（基于向量检索）
     */
    private String chatWithRag(String question, String conversationId, String username) {
        List<Document> docs = null;
        try {
            // 1. 向量检索
            SearchRequest.Builder request = SearchRequest.builder()
                            .query(question)
                            .topK(4)
                            .similarityThreshold(0.75)
                            .filterExpression(new FilterExpressionBuilder().eq("username", username).build());
            docs = vectorStore.similaritySearch(request.build());
            if (docs != null) {
                docs = docs.stream()
                        .filter(doc -> username.equals(String.valueOf(doc.getMetadata().get("username"))))
                        .toList();
            }
        } catch (Exception e) {
            // 向量检索异常，记录日志
            log.error("RAG检索失败: {}", e.getMessage());
            // 降级到普通模式
            return chatNormal(question, conversationId, username);
        }

        String context = docs == null || docs.isEmpty() ? "无相关参考资料" :
                docs.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));

        // 2. 动态注入 System Prompt
        String systemPrompt = "你是一个专业AI助手。请严格基于以下参考资料回答：\n" + context +
                "\n\n如果资料中没有相关信息，请明确告知用户，不要编造答案。";

        // 3. 根据场景选择模型
        ChatClient client = modelService.getChatClient();

        // 4. 构建带记忆的客户端并调用
        client = client.mutate()
                .defaultSystem(systemPrompt)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory)
                        .conversationId(conversationId)
                        .build())
                .build();

        UserContext.setUsername(username);
        try {
            String answer = client.prompt().user(question).call().content();
            if (answer == null || answer.isBlank()) {
                throw new ModelCallException("模型返回空响应");
            }
            chatMemory.addConversation(username, conversationId, question.substring(0, Math.min(question.length(), 30)));
            return answer;
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 普通闲聊模式
     */
    private String chatNormal(String question, String conversationId, String username) {
        // 根据场景选择模型
        ChatClient client = modelService.getChatClient();

        client = client.mutate()
                .defaultSystem("你是一个乐于助人的AI助手，请清晰、专业地回答用户问题。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory)
                        .conversationId(conversationId)
                        .build())
                .build();
        // 【核心修改点】同样在这里设置 username
        UserContext.setUsername(username);
        try {
            String answer = client.prompt().user(question).call().content();
            if (answer == null || answer.isBlank()) {
                throw new ModelCallException("模型返回空响应");
            }
            // 关联用户与对话
            chatMemory.addConversation(username, conversationId, question.substring(0, Math.min(question.length(), 30)));
            return answer;
        } finally {
            UserContext.clear();
        }
    }
}
