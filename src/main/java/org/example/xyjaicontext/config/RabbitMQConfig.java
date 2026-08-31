package org.example.xyjaicontext.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类
 *
 * 队列架构说明：
 * ┌─────────────┐     ┌──────────────┐     ┌─────────────┐
 * │  主队列      │────▶│  重试队列     │────▶│  主队列      │
 * │ (document)  │ nack│  (retry)     │ TTL │ (document)  │
 * └──────┬──────┘     └──────────────┘     └──────┬──────┘
 *        │ nack(超过3次)                          │
 *        ▼                                        │
 * ┌─────────────┐                                │
 * │  死信队列    │◀───────────────────────────────┘
 * │   (DLQ)     │
 * └─────────────┘
 */
@Configuration
public class RabbitMQConfig {

    // ==================== 主队列相关 ====================
    /** 主队列名称：处理文档的队列 */
    public static final String DOCUMENT_QUEUE = "document.process.queue";
    /** 主交换机名称 */
    public static final String DOCUMENT_EXCHANGE = "document.process.exchange";

    // ==================== 重试队列相关 ====================
    /** 重试队列名称：消费失败的消息先进入这里 */
    public static final String RETRY_QUEUE = "document.process.retry.queue";
    /** 重试交换机名称 */
    public static final String RETRY_EXCHANGE = "document.process.retry.exchange";
    /** 重试路由键 */
    public static final String RETRY_ROUTING_KEY = "document.process.retry";

    // ==================== 死信队列相关 ====================
    /** 死信队列名称：重试多次仍失败的消息最终进入这里 */
    public static final String DEAD_LETTER_QUEUE = "document.process.dlq";
    /** 死信交换机名称 */
    public static final String DEAD_LETTER_EXCHANGE = "document.process.dlx";

    /**
     * 主队列配置
     *
     * 关键参数：
     * - durable: true 队列持久化，RabbitMQ重启后队列不丢失
     * - x-dead-letter-exchange: 指定死信交换机为"重试交换机"
     * - x-dead-letter-routing-key: 指定死信路由键
     *
     * 工作流程：
     * 1. 消费者 basicNack(requeue=false) → 消息变成"死信"
     * 2. 死信被转发到 RETRY_EXCHANGE
     * 3. 根据 RETRY_ROUTING_KEY 路由到 RETRY_QUEUE
     */
    @Bean
    public Queue documentQueue() {
        return QueueBuilder.durable(DOCUMENT_QUEUE)
                .withArgument("x-dead-letter-exchange", RETRY_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RETRY_ROUTING_KEY)
                // 2. 【新增】设置队列最大长度为10000条
                .withArgument("x-max-length", 10000)
                // 3. 【新增】超出限制后，拒绝发布新消息
                .withArgument("x-overflow", "reject-publish")
                .build();
    }

    /**
     * 重试队列配置
     *
     * 关键参数：
     * - x-message-ttl: 5000ms (5秒)，消息在队列中最多停留5秒
     * - x-dead-letter-exchange: 指定死信交换机为"主交换机"
     * - x-dead-letter-routing-key: 指定死信路由键为主队列路由键
     *
     * 工作流程：
     * 1. 消息进入重试队列后等待5秒（TTL）
     * 2. TTL过期后，消息变成"死信"
     * 3. 死信被转发到 DOCUMENT_EXCHANGE
     * 4. 根据路由键重新回到 DOCUMENT_QUEUE
     * 5. 消费者再次尝试处理
     */
    @Bean
    public Queue retryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", DOCUMENT_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DOCUMENT_QUEUE)
                .withArgument("x-message-ttl", 5000)
                .build();
    }

    /**
     * 死信队列配置
     *
     * 特点：
     * - 没有设置死信交换机，消息进入后不会再转发
     * - 需要人工监控和处理这些"毒药消息"
     * - 通常配置告警，通知开发人员介入
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    /**
     * 主交换机：Direct类型，精确匹配路由键
     */
    @Bean
    public DirectExchange documentExchange() {
        return new DirectExchange(DOCUMENT_EXCHANGE, true, false);
    }

    /**
     * 死信交换机：接收从主队列和重试队列转发的死信
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * 重试交换机：将消息从重试队列转发回主队列
     */
    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(RETRY_EXCHANGE, true, false);
    }

    /**
     * 绑定：主队列 ←→ 主交换机
     * 路由键：document.process.queue
     */
    @Bean
    public Binding documentBinding() {
        return BindingBuilder.bind(documentQueue())
                .to(documentExchange())
                .with(DOCUMENT_QUEUE);
    }

    /**
     * 绑定：死信队列 ←→ 死信交换机
     * 路由键：document.process.dlq
     */
    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with(DEAD_LETTER_QUEUE);
    }

    /**
     * 绑定：重试队列 ←→ 重试交换机
     * 路由键：document.process.retry
     */
    @Bean
    public Binding retryBinding() {
        return BindingBuilder.bind(retryQueue())
                .to(retryExchange())
                .with(RETRY_ROUTING_KEY);
    }
}
