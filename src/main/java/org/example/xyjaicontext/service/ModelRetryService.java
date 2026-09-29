package org.example.xyjaicontext.service;

import lombok.extern.slf4j.Slf4j;
import org.example.xyjaicontext.config.ModelRetryProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Centralized, bounded retry logic for model calls. */
@Slf4j
@Service
public class ModelRetryService {

    private static final double JITTER_RATIO = 0.20;

    private final ModelRetryProperties properties;

    public ModelRetryService(ModelRetryProperties properties) {
        this.properties = properties;
        validate("chat", properties.getChat());
        validate("document", properties.getDocument());
    }

    public <T> T executeChat(Supplier<T> operation) {
        return execute("chat", properties.getChat(), operation);
    }

    public <T> T executeDocument(Supplier<T> operation) {
        return execute("document", properties.getDocument(), operation);
    }

    private <T> T execute(String workload,
                          ModelRetryProperties.Policy policy,
                          Supplier<T> operation) {
        long deadline = System.nanoTime() + policy.getTotalTimeout().toNanos();
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException failure) {
                lastFailure = failure;
                boolean canRetry = isRetryable(failure)
                        && attempt < policy.getMaxAttempts()
                        && remainingNanos(deadline) > 0;
                if (!canRetry) {
                    throw failure;
                }

                long delayMillis = calculateBackoffMillis(policy, attempt);
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos(deadline));
                if (remainingMillis <= 0 || delayMillis > remainingMillis) {
                    throw failure;
                }

                log.debug("模型{}调用失败，第 {}/{} 次尝试将在 {}ms 后重试: {}",
                        workload, attempt, policy.getMaxAttempts(), delayMillis, failure.getMessage());
                sleep(delayMillis);
            }
        }

        // The loop either returns a value or throws the last failure. This guard
        // keeps the method total if the policy is changed in the future.
        throw lastFailure == null
                ? new ModelCallException("模型调用失败")
                : lastFailure;
    }

    private boolean isRetryable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ModelOverloadedException) {
                return false;
            }
            if (current instanceof RetryableModelException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private long calculateBackoffMillis(ModelRetryProperties.Policy policy, int failedAttempt) {
        long initialMillis = policy.getInitialBackoff().toMillis();
        long maxMillis = policy.getMaxBackoff().toMillis();
        if (initialMillis <= 0 || maxMillis <= 0) {
            return 0;
        }

        long baseMillis = initialMillis;
        for (int i = 1; i < failedAttempt && baseMillis < maxMillis; i++) {
            if (baseMillis > maxMillis / 2) {
                baseMillis = maxMillis;
            } else {
                baseMillis *= 2;
            }
        }
        baseMillis = Math.min(baseMillis, maxMillis);

        long jitter = Math.max(1, (long) (baseMillis * JITTER_RATIO));
        long lowerBound = Math.max(0, baseMillis - jitter);
        long upperBound = Math.min(maxMillis, baseMillis + jitter);
        return lowerBound >= upperBound
                ? lowerBound
                : ThreadLocalRandom.current().nextLong(lowerBound, upperBound + 1);
    }

    private void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("模型重试被中断", interrupted);
        }
    }

    private long remainingNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    private void validate(String workload, ModelRetryProperties.Policy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("模型" + workload + "重试参数缺失");
        }
        Duration initial = policy.getInitialBackoff();
        Duration maximum = policy.getMaxBackoff();
        Duration total = policy.getTotalTimeout();
        if (policy.getMaxAttempts() < 1
                || initial == null || maximum == null || total == null
                || initial.isNegative() || maximum.isNegative()
                || maximum.compareTo(initial) < 0
                || total.isNegative() || total.isZero()) {
            throw new IllegalArgumentException("模型" + workload + "重试参数非法");
        }
    }
}
