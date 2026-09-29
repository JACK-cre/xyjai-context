package org.example.xyjaicontext.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded executors isolate interactive chat traffic from document workloads. */
@Configuration
public class ModelExecutorConfig {

    @Bean(name = "chatModelExecutor")
    public ThreadPoolTaskExecutor chatModelExecutor(
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${agent.model.executor.chat.core-size:8}") int coreSize,
            @Value("${agent.model.executor.chat.max-size:32}") int maxSize,
            @Value("${agent.model.executor.chat.queue-capacity:100}") int queueCapacity) {
        return createExecutor("chat-model-", "chat", coreSize, maxSize, queueCapacity, meterRegistry);
    }

    @Bean(name = "documentModelExecutor")
    public ThreadPoolTaskExecutor documentModelExecutor(
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${agent.model.executor.document.core-size:4}") int coreSize,
            @Value("${agent.model.executor.document.max-size:16}") int maxSize,
            @Value("${agent.model.executor.document.queue-capacity:50}") int queueCapacity) {
        return createExecutor("document-model-", "document", coreSize, maxSize, queueCapacity, meterRegistry);
    }

    @Bean(name = "conversationSummaryExecutor")
    public ThreadPoolTaskExecutor conversationSummaryExecutor(
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${agent.context.summary.executor.core-size:1}") int coreSize,
            @Value("${agent.context.summary.executor.max-size:4}") int maxSize,
            @Value("${agent.context.summary.executor.queue-capacity:100}") int queueCapacity) {
        return createExecutor("conversation-summary-", "conversation-summary",
                coreSize, maxSize, queueCapacity, meterRegistry);
    }

    private ThreadPoolTaskExecutor createExecutor(String threadNamePrefix,
                                                   String poolName,
                                                   int coreSize,
                                                   int maxSize,
                                                   int queueCapacity,
                                                   ObjectProvider<MeterRegistry> meterRegistry) {
        if (coreSize < 1 || maxSize < coreSize || queueCapacity < 1) {
            throw new IllegalArgumentException("线程池参数非法: " + poolName);
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        AtomicInteger rejectedTasks = new AtomicInteger();
        executor.setRejectedExecutionHandler((task, pool) -> {
            rejectedTasks.incrementAndGet();
            new ThreadPoolExecutor.AbortPolicy().rejectedExecution(task, pool);
        });
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();

        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            Tags tags = Tags.of("pool", poolName);
            Gauge.builder("agent.model.executor.active", executor, ThreadPoolTaskExecutor::getActiveCount)
                    .tags(tags).description("Active model executor threads").register(registry);
            Gauge.builder("agent.model.executor.pool.size", executor, ThreadPoolTaskExecutor::getPoolSize)
                    .tags(tags).description("Current model executor pool size").register(registry);
            Gauge.builder("agent.model.executor.queue.size", executor, ThreadPoolTaskExecutor::getQueueSize)
                    .tags(tags).description("Queued model tasks").register(registry);
            Gauge.builder("agent.model.executor.rejected", rejectedTasks, AtomicInteger::get)
                    .tags(tags).description("Rejected model tasks").register(registry);
        }
        return executor;
    }
}
