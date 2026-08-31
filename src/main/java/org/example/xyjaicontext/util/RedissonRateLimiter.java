package org.example.xyjaicontext.util;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.redisson.api.RateIntervalUnit;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Component
public class RedissonRateLimiter {

    private final RedissonClient redissonClient;

    /**
     * 检查请求是否被允许
     *
     * @param key           限流键（例如：userId, IP, 或 "global_api"）
     * @param maxRequests   时间窗口内允许的最大请求数
     * @param windowSeconds 时间窗口大小（秒）
     * @return true 允许通过，false 被限流
     */
    public boolean isAllowed(String key, int maxRequests, int windowSeconds) {
        String limiterKey = "rate:limit:" + key;
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(limiterKey);

        // 1. 设置限流规则
        // 注意：trySetRate 是幂等的，如果 Key 已存在且规则未变，不会重置令牌桶
        rateLimiter.trySetRate(
                RateType.OVERALL,
                maxRequests,
                windowSeconds,
                RateIntervalUnit.SECONDS
        );

        // 2. 【重要】设置过期时间
        // 防止针对大量不同 Key（如 userId）限流时，Redis 内存泄漏。
        // 如果该 Key 在 2 倍窗口时间内没有被访问，则自动删除限流器对象。
        // 这样可以节省大量 Redis 内存。
        rateLimiter.expire(windowSeconds * 2, TimeUnit.SECONDS);

        // 3. 尝试获取令牌
        // tryAcquire() 会立即返回，不阻塞
        return rateLimiter.tryAcquire();
    }
}