package org.example.xyjaicontext.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ModelService {

    private final ChatClient.Builder chatClientBuilder;
    private final ThreadPoolTaskExecutor chatModelExecutor;
    private final ThreadPoolTaskExecutor documentModelExecutor;
    private final ModelRetryService modelRetryService;

    @Value("${agent.model.timeout-seconds:60}")
    private long timeoutSeconds;

    public ModelService(ChatClient.Builder chatClientBuilder,
                        @Qualifier("chatModelExecutor") ThreadPoolTaskExecutor chatModelExecutor,
                        @Qualifier("documentModelExecutor") ThreadPoolTaskExecutor documentModelExecutor,
                        ModelRetryService modelRetryService) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatModelExecutor = chatModelExecutor;
        this.documentModelExecutor = documentModelExecutor;
        this.modelRetryService = modelRetryService;
    }

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
        return modelRetryService.executeChat(
                () -> invokeOnce(chatModelExecutor, systemPrompt, userPrompt, allowEmpty));
    }

    /** Uses the lower-priority document pool and its separate retry budget. */
    public String callDocument(String systemPrompt, String userPrompt, boolean allowEmpty) {
        return modelRetryService.executeDocument(
                () -> invokeOnce(documentModelExecutor, systemPrompt, userPrompt, allowEmpty));
    }

    private String invokeOnce(ThreadPoolTaskExecutor executor,
                              String systemPrompt,
                              String userPrompt,
                              boolean allowEmpty) {
        CompletableFuture<String> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                String content = getChatClient().prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .content();
                return content == null ? "" : content.trim();
            }, executor);
        } catch (RejectedExecutionException e) {
            throw new ModelOverloadedException("模型线程池已满，请稍后重试", e);
        }
        try {
            String content = future.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!allowEmpty && content.isBlank()) {
                throw new RetryableModelException("模型返回空响应");
            }
            return content;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ModelTimeoutException("模型调用超时（" + timeoutSeconds + "秒）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("模型调用被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ModelCallException modelCallException) {
                throw modelCallException;
            }
            if (isRetryable(cause)) {
                throw new RetryableModelException("模型临时调用失败: " + cause.getMessage(), cause);
            }
            throw new ModelCallException("模型调用失败: " + cause.getMessage(), cause);
        }
    }

    private boolean isRetryable(Throwable cause) {
        if (cause instanceof java.net.SocketTimeoutException
                || cause instanceof java.net.ConnectException
                || cause instanceof java.io.IOException
                || cause instanceof org.springframework.web.client.ResourceAccessException) {
            return true;
        }
        if (cause instanceof org.springframework.web.client.RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 408 || status == 429 || status >= 500;
        }
        return false;
    }
}
