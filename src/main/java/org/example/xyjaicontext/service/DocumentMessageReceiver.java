package org.example.xyjaicontext.service;

import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.xyjaicontext.config.RabbitMQConfig;
import org.example.xyjaicontext.mapper.DocRecordMapper;
import org.example.xyjaicontext.model.DocRecord;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 文档消息消费者
 *
 * 核心职责：
 * 1. 从 RabbitMQ 主队列接收文档消息
 * 2. 实现幂等性检查，避免重复消费
 * 3. 解析文档、分片、向量化并存储到向量数据库
 * 4. 消费失败时自动重试（最多3次）
 * 5. 超过最大重试次数后手动发送到死信队列
 *
 * 重试机制说明：
 * - 第1-2次失败：basicNack → 重试队列（延迟5秒）→ 回到主队列
 * - 第3次失败：手动发送到死信队列 → basicAck 确认原消息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentMessageReceiver {

    /** 向量存储服务，用于存储文档向量 */
    private final VectorStore vectorStore;

    /** 文档记录 Mapper，用于持久化处理状态 */
    private final DocRecordMapper docMapper;

    /** Redis 模板，用于幂等性检查和重试计数 */
    private final StringRedisTemplate redisTemplate;

    /** RabbitMQ 模板，用于手动发送消息到死信队列 */
    private final RabbitTemplate rabbitTemplate;

    /** 消费端最大重试次数 */
    private static final int MAX_CONSUME_RETRY = 3;

    /** Redis 中存储重试次数的键前缀 */
    private static final String RETRY_COUNT_PREFIX = "doc:retry:";

    /**
     * 处理文档消息
     *
     * @RabbitListener 注解说明：
     * - queues: 监听的队列名称为 "document.process.queue"
     * - concurrency: 并发消费者数量 "2-4"
     *   • 最少启动 2 个消费者线程
     *   • 根据负载动态扩展，最多 4 个消费者线程
     *
     * @Transactional 保证数据库操作的原子性
     *
     * @param message 消息体（Spring 自动反序列化为 DocumentMessage 对象）
     * @param channel RabbitMQ 通道，用于手动 ACK/NACK
     * @param amqpMessage 原始 AMQP 消息，包含 DeliveryTag 等元数据
     */
    @RabbitListener(queues = "document.process.queue", concurrency = "2-4")
    @Transactional
    public void processDocument(DocumentMessageSender.DocumentMessage message, Channel channel, Message amqpMessage) throws IOException {
        String taskId = message.getTaskId();
        String statusKey = "doc:status:" + taskId;
        String retryCountKey = RETRY_COUNT_PREFIX + taskId;

        log.info("📨 收到消息: {}, DeliveryTag: {}", taskId, amqpMessage.getMessageProperties().getDeliveryTag());

        // ==================== 步骤1：幂等性检查 ====================
        /**
         * 幂等性作用：
         * - 防止网络抖动导致的消息重复投递
         * - 如果 Redis 中存在 "doc:processed:{taskId}"，说明该消息已处理成功
         * - 直接 ACK 确认，跳过后续处理逻辑
         */
        if (redisTemplate.hasKey("doc:processed:" + taskId)) {
            log.info("✅ 消息已处理，跳过（幂等性）: {}", taskId);
            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
            return;
        }

        // ==================== 步骤2：获取重试次数 ====================
        int retryCount = getRetryCount(retryCountKey);
        log.info("当前重试次数: {}/{}", retryCount, MAX_CONSUME_RETRY);

        // ==================== 步骤3：创建数据库记录 ====================
        /**
         * 在开始处理前先在数据库中创建记录
         * 状态 0 表示"处理中"
         */
        DocRecord record = new DocRecord();
        record.setFileName(message.getFileName());
        record.setStatus(0);
        docMapper.insert(record);

        File tempFile = null;
        try {
            log.info("🔄 开始解析文档: {}, 第{}次尝试", message.getFileName(), retryCount + 1);

            // 创建临时文件存储上传的文档内容
            tempFile = File.createTempFile("upload_", "_" + message.getFileName());
            Files.write(tempFile.toPath(), message.getFileContent());
            // ==================== 步骤4：文档解析 ====================
            TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(message.getFileContent()));
            List<Document> docs = reader.get();

            // ==================== 步骤5：文本分片 ====================
            /**
             * 将长文档分割成多个小块（chunks）
             * 避免单个文档过大影响向量检索效果
             */
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> chunks = splitter.apply(docs);

            // 为每个分片添加元数据，标记来源文件
            chunks.forEach(d -> d.getMetadata().put("source", message.getFileName()));

            // ==================== 步骤6：存储到向量数据库 ====================
            vectorStore.add(chunks);
            log.info("✅ 文档向量化完成, 共 {} 个 chunks", chunks.size());

            // ==================== 步骤7：更新处理状态 ====================
            // 更新数据库状态为 1（成功）
            docMapper.updateStatus(record.getId(), 1, null);

            // 更新 Redis 状态为完成
            redisTemplate.opsForValue().set(statusKey, "COMPLETED");

            // 设置幂等性标识，24小时过期
            redisTemplate.opsForValue().set("doc:processed:" + taskId, "true", 24, TimeUnit.HOURS);

            // 删除重试计数，释放 Redis 空间
            redisTemplate.delete(retryCountKey);

            // 手动确认消息，告诉 RabbitMQ 这条消息已成功处理
            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
            log.info("✅ 消息确认成功: {}", taskId);

        } catch (Exception e) {
            // ==================== 步骤8：异常处理 ====================
            log.error("❌ 文档处理失败: {}, 第{}次尝试", e.getMessage(), retryCount + 1, e);

            // 更新数据库状态为 2（失败），并记录错误信息
            docMapper.updateStatus(record.getId(), 2, e.getMessage());

            // 增加重试次数计数
            incrementRetryCount(retryCountKey);
            int newRetryCount = retryCount + 1;

            /**
             * 判断是否达到最大重试次数
             */
            if (newRetryCount >= MAX_CONSUME_RETRY) {
                // ========== 情况A：达到最大重试次数，进入死信队列 ==========
                log.error("❌ 达到最大重试次数({})，手动发送到死信队列: {}", MAX_CONSUME_RETRY, taskId);

                // 更新 Redis 状态为失败
                redisTemplate.opsForValue().set(statusKey, "FAILED: " + e.getMessage(), 24, TimeUnit.HOURS);

                // 手动发送消息到死信队列
                sendToDeadLetterQueue(message);

                // 确认原消息，避免重复消费
                channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);

                // 删除重试计数
                redisTemplate.delete(retryCountKey);
            } else {
                // ========== 情况B：未达到最大重试次数，进入重试队列 ==========
                log.warn("⚠️ 消息将进入重试队列: {}, 当前重试次数: {}/{}", taskId, newRetryCount, MAX_CONSUME_RETRY);

                // 更新 Redis 状态为重试中
                redisTemplate.opsForValue().set(statusKey, "RETRYING(" + newRetryCount + "/" + MAX_CONSUME_RETRY + "): " + e.getMessage(), 24, TimeUnit.HOURS);

                /**
                 * 拒绝消息，不重新入队（requeue=false）
                 *
                 * RabbitMQ 会根据主队列的死信配置自动转发：
                 * x-dead-letter-exchange = retry.exchange
                 * x-dead-letter-routing-key = document.process.retry
                 *
                 * 消息流向：
                 * 主队列 → 重试交换机 → 重试队列 → (等待5秒TTL) → 主交换机 → 主队列
                 */
                channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(), false, false);
            }
        } finally {
            // ==================== 步骤9：清理临时文件 ====================
            /**
             * 无论成功或失败，都要删除临时文件
             * 避免磁盘空间泄漏
             */
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile.toPath());
                } catch (IOException ignored) {
                    // 忽略删除失败的异常，避免影响主流程
                }
            }
        }
    }

    /**
     * 手动发送消息到死信队列
     *
     * 使用场景：
     * - 消费重试次数已达上限（>= 3次）
     * - 需要人工介入处理的"毒药消息"
     *
     * 为什么不用 basicNack？
     * - basicNack 会触发主队列的死信配置，指向重试队列
     * - 我们需要绕过这个配置，直接发送到死信队列
     * - 所以使用 RabbitTemplate.convertAndSend() 手动指定目标交换机和队列
     *
     * @param message 需要发送到死信队列的消息
     */
    private void sendToDeadLetterQueue(DocumentMessageSender.DocumentMessage message) {
        try {
            // 直接发送到死信交换机和死信队列
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.DEAD_LETTER_EXCHANGE,  // 死信交换机
                    RabbitMQConfig.DEAD_LETTER_QUEUE,     // 死信队列
                    message
            );
            log.info("✅ 消息已手动发送到死信队列: {}", message.getTaskId());
        } catch (Exception e) {
            // 即使发送到死信队列失败，也要记录日志
            // 原消息已经通过 basicAck 确认，不会重复消费
            log.error("❌ 发送消息到死信队列失败: {}", message.getTaskId(), e);
        }
    }

    /**
     * 从 Redis 获取当前重试次数
     *
     * @param key Redis 键，格式为 "doc:retry:{taskId}"
     * @return 当前重试次数，如果不存在则返回 0
     */
    private int getRetryCount(String key) {
        String count = redisTemplate.opsForValue().get(key);
        return count != null ? Integer.parseInt(count) : 0;
    }

    /**
     * 增加重试次数计数
     *
     * @param key Redis 键，格式为 "doc:retry:{taskId}"
     */
    private void incrementRetryCount(String key) {
        // Redis 原子自增操作
        Long count = redisTemplate.opsForValue().increment(key, 1);

        // 设置过期时间为 24 小时，避免 Redis 内存泄漏
        redisTemplate.expire(key, 24, TimeUnit.HOURS);

        log.debug("重试次数已更新: {} = {}", key, count);
    }
}
