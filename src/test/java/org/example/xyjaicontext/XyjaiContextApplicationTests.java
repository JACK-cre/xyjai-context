package org.example.xyjaicontext;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.ai.vectorstore.VectorStore;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class XyjaiContextApplicationTests {

    // 集成环境才启动 Chroma；上下文测试使用替身避免依赖本机外部服务。
    @MockBean
    VectorStore vectorStore;

    @MockBean
    RedissonClient redissonClient;

    @MockBean
    StringRedisTemplate stringRedisTemplate;

    @MockBean
    RabbitTemplate rabbitTemplate;

    @Test
    void contextLoads() {
    }

}
