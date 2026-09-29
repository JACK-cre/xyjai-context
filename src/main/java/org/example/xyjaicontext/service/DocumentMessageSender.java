package org.example.xyjaicontext.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.xyjaicontext.document.DetectedDocumentType;
import org.example.xyjaicontext.document.DocumentFileValidator;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.Serializable;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 文档消息发送者
 *
 * 职责：
 * 1. 将上传的文档封装成消息发送到 RabbitMQ
 * 2. 提供发送端重试机制（网络异常时重试）
 * 3. 通过 Confirm 回调确认消息是否到达 Exchange
 * 4. 通过 Return 回调确认消息是否路由到 Queue
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentMessageSender {

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final DocumentWorkflowService documentWorkflowService;
    private final DocumentFileValidator documentFileValidator;

    @Value("${rabbitmq.enabled:true}")
    private boolean rabbitmqEnabled;

    /** 发送端最大重试次数（针对网络异常） */
    private static final int MAX_SEND_RETRY = 3;
    /** 重试间隔基数（第1次等1s，第2次等2s，第3次等3s） */
    private static final long RETRY_DELAY_MS = 1000;

    /**
     * 初始化 RabbitMQ 回调机制
     *
     * 重要：必须在 @PostConstruct 中设置，保证只设置一次
     * 如果在每次发送时设置会导致线程安全问题
     */
    @PostConstruct
    public void init() {
        // ==================== Confirm 回调 ====================
        /**
         * Confirm 回调作用：
         * - 确认消息是否成功到达 Exchange
         * - ack=true: 消息已到达 Exchange
         * - ack=false: 消息未到达 Exchange（Broker 故障、权限问题等）
         *
         * 注意：
         * - 这是异步回调，消息发送后立即返回，回调稍后执行
         * - Confirm 失败不会触发自动重试，只记录状态供后续补偿
         */
        rabbitTemplate.setConfirmCallback((correlation, ack, cause) -> {
            if (correlation == null || correlation.getId() == null) {
                log.error("Confirm回调：correlation或ID为空");
                return;
            }

            String taskId = correlation.getId();
            String statusKey = "doc:status:" + taskId;

            if (ack) {
                // ✅ 消息成功到达 Exchange
                log.info("✅ Confirm成功 - 消息已到达Exchange: {}", taskId);
            } else {
                // ❌ 消息未到达 Exchange，记录失败状态
                log.error("❌ Confirm失败 - 消息未到达Exchange: {}, 原因: {}", taskId, cause);
                redisTemplate.opsForValue().set(
                        statusKey,
                        "FAILED: Confirm失败-" + cause,
                        24,
                        TimeUnit.HOURS
                );
            }
        });

        // ==================== Return 回调 ====================
        /**
         * Return 回调作用：
         * - 确认消息是否从 Exchange 成功路由到 Queue
         * - 触发条件：消息到达 Exchange，但没有匹配的队列
         * - 常见原因：RoutingKey 错误、队列未绑定等配置问题
         *
         * 注意：
         * - 必须设置 rabbitTemplate.setMandatory(true) 才会触发
         * - Return 失败也不会触发自动重试，只记录日志
         */
        rabbitTemplate.setReturnsCallback(returned -> {
            Message message = returned.getMessage();
            int replyCode = returned.getReplyCode();
            String replyText = returned.getReplyText();
            String exchange = returned.getExchange();
            String routingKey = returned.getRoutingKey();

            String messageId = message.getMessageProperties().getMessageId();
            log.error("❌ Return回调 - 消息路由失败: {}, 回复码: {}, 原因: {}, Exchange: {}, RoutingKey: {}",
                    messageId, replyCode, replyText, exchange, routingKey);

            String statusKey = "doc:status:" + messageId;
            redisTemplate.opsForValue().set(
                    statusKey,
                    "FAILED: 路由失败(code=" + replyCode + ")-" + replyText,
                    24,
                    TimeUnit.HOURS
            );
        });

        // 启用 mandatory 模式，确保路由失败时触发 Return 回调
        rabbitTemplate.setMandatory(true);

        log.info("RabbitMQ回调机制初始化完成");
    }

    /**
     * 发送文档进行处理
     *
     * @param file 上传的文件
     * @return taskId 任务ID，用于后续查询处理状态
     */
    public String sendDocumentForProcessing(MultipartFile file) throws IOException {
        return sendDocumentForProcessing(file, "anonymous");
    }

    public String sendDocumentForProcessing(MultipartFile file, String username) throws IOException {
        byte[] content = file.getBytes();
        DetectedDocumentType detected = documentFileValidator.validate(file.getOriginalFilename(), content);
        // 生成唯一任务ID
        String taskId = UUID.randomUUID().toString();
        String statusKey = "doc:status:" + taskId;

        // 在 Redis 中记录初始状态
        redisTemplate.opsForValue().set(statusKey, "PROCESSING", 24, TimeUnit.HOURS);
        redisTemplate.opsForValue().set("doc:owner:" + taskId,
                username == null ? "anonymous" : username, 24, TimeUnit.HOURS);

        // 封装消息对象
        DocumentMessage message = new DocumentMessage();
        message.setTaskId(taskId);
        message.setUsername(username);
        message.setFileName(detected.fileName());
        message.setFileContent(content);

        if (rabbitmqEnabled) {
            try {
                // 带重试的发送逻辑
                sendWithRetry(message, statusKey);
            } catch (Exception e) {
                log.error("❌ MQ发送失败，已达到最大重试次数: {}", taskId, e);
                redisTemplate.opsForValue().set(statusKey, "FAILED: MQ发送失败", 24, TimeUnit.HOURS);
                throw new RuntimeException("文档处理失败：MQ服务不可用", e);
            }
        } else {
            log.warn("MQ已禁用，使用同步处理");
            try {
                documentWorkflowService.process(taskId, username, message.getFileName(), message.getFileContent());
            } catch (Exception e) {
                redisTemplate.opsForValue().set(statusKey, "FAILED: " + e.getMessage(), 24, TimeUnit.HOURS);
                throw new RuntimeException("文档同步处理失败", e);
            }
        }

        return taskId;
    }

    /**
     * 带重试机制的消息发送
     *
     * 重试场景：
     * - 网络断开
     * - RabbitMQ 服务器不可达
     * - 连接超时
     *
     * 不重试场景：
     * - Confirm 失败（消息已发送，只是 Broker 返回 nack）
     * - Return 失败（消息已路由，但配置有问题）
     *
     * @param message 消息对象
     * @param statusKey Redis 状态键
     */
    private void sendWithRetry(DocumentMessage message, String statusKey) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_SEND_RETRY; attempt++) {
            try {
                // 创建关联数据，用于 Confirm 回调中识别消息
                CorrelationData correlationData = new CorrelationData(message.getTaskId());

                // 发送消息到交换机
                rabbitTemplate.convertAndSend(
                        "document.process.exchange",
                        "document.process.queue",
                        message,
                        correlationData
                );

                log.info("📤 消息已发送: {}, 第{}次尝试", message.getTaskId(), attempt);
                // 发送成功，直接返回
                return;

            } catch (Exception e) {
                // 捕获发送异常（网络问题、连接问题等）
                lastException = e;
                log.warn("⚠️ 消息发送异常, 第{}/{}次重试: {}", attempt, MAX_SEND_RETRY, e.getMessage());

                if (attempt < MAX_SEND_RETRY) {
                    try {
                        // 指数退避：第1次等1s，第2次等2s，第3次等3s
                        long delay = RETRY_DELAY_MS * attempt;
                        log.info("等待{}ms后重试...", delay);
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("发送中断", ie);
                    }
                }
            }
        }

        // 所有重试都失败了，抛出异常
        throw new RuntimeException("消息发送失败，已重试" + MAX_SEND_RETRY + "次", lastException);
    }

    /**
     * 文档消息对象
     *
     * 必须实现 Serializable 接口，因为要在网络间传输
     */
    public static class DocumentMessage implements Serializable {
        private String taskId;
        private String username;
        private String fileName;
        private byte[] fileContent;

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public byte[] getFileContent() {
            return fileContent;
        }

        public void setFileContent(byte[] fileContent) {
            this.fileContent = fileContent;
        }
    }
}
