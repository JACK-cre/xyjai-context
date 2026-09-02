package org.example.xyjaicontext.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
public class ModelService {

    private final ChatClient.Builder chatClientBuilder;

    @Value("${agent.model.timeout-seconds:60}")
    private long timeoutSeconds;

    /**
     * 获取默认模型的ChatClient
     */
    public ChatClient getChatClient() {
        return chatClientBuilder
                .build();
    }

    /**
     * Executes a model request with an upper bound so a graph node cannot hold
     * a Rabbit worker or HTTP request forever.
     */
    public String call(String systemPrompt, String userPrompt, boolean allowEmpty) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            String content = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            return content == null ? "" : content.trim();
        });
        try {
            String content = future.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!allowEmpty && content.isBlank()) {
                throw new ModelCallException("模型返回空响应");
            }
            return content;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ModelCallException("模型调用超时（" + timeoutSeconds + "秒）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("模型调用被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ModelCallException modelCallException) {
                throw modelCallException;
            }
            throw new ModelCallException("模型调用失败: " + cause.getMessage(), cause);
        }
    }
}
