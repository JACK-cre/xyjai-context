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
import org.springframework.stereotype.Service;

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

    /**
     * 统一对话入口
     */
    public String chat(String question, boolean useRag, String conversationId, String username) {
        return useRag ? chatWithRag(question, conversationId, username) : chatNormal(question, conversationId, username);
    }

    /**
     * RAG 模式（基于向量检索）
     */
    private String chatWithRag(String question, String conversationId, String username) {
        List<Document> docs = null;
        try {
            // 1. 向量检索
            docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(4)
                            .similarityThreshold(0.75)
                            .build()
            );
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
        String answer = client.prompt().user(question).call().content();
        chatMemory.addConversation(username, conversationId, question.substring(0, Math.min(question.length(), 30)));
        UserContext.clear();
        return answer;
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
        String answer = client.prompt().user(question).call().content();
        // 关联用户与对话
        chatMemory.addConversation(username, conversationId, question.substring(0, Math.min(question.length(), 30)));
        UserContext.clear();
        return answer;
    }
}