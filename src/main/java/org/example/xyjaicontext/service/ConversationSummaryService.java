package org.example.xyjaicontext.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.xyjaicontext.config.ContextSummaryProperties;
import org.example.xyjaicontext.mapper.ConversationSummaryMapper;
import org.example.xyjaicontext.memory.RedisChatMemory;
import org.example.xyjaicontext.model.ConversationSummary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Maintains a durable long-term summary beside the recent message window. */
@Slf4j
@Service
public class ConversationSummaryService {

    private static final String SUMMARY_KEY_PREFIX = "chat:summary:";
    private static final String PENDING_KEY_PREFIX = "chat:summary:pending:";
    private static final String LOCK_KEY_PREFIX = "chat:summary:lock:";
    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是会话摘要 Agent。请把历史对话压缩为可供后续对话使用的长期摘要。
            只保留用户目标、关键事实、已经确认的结论、约束条件、用户偏好和未完成事项。
            不要编造，不要重复，不要输出解释，只返回摘要正文。
            """;

    private final RedisChatMemory chatMemory;
    private final ConversationSummaryMapper summaryMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ModelService modelService;
    private final ContextSummaryProperties properties;
    private final ThreadPoolTaskExecutor summaryExecutor;

    public ConversationSummaryService(RedisChatMemory chatMemory,
                                      ConversationSummaryMapper summaryMapper,
                                      StringRedisTemplate redisTemplate,
                                      ObjectMapper objectMapper,
                                      ModelService modelService,
                                      ContextSummaryProperties properties,
                                      @Qualifier("conversationSummaryExecutor") ThreadPoolTaskExecutor summaryExecutor) {
        this.chatMemory = chatMemory;
        this.summaryMapper = summaryMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.modelService = modelService;
        this.properties = properties;
        this.summaryExecutor = summaryExecutor;
        validateProperties();
    }

    /** Loads a tenant-scoped summary and the most recent raw messages for Graph state. */
    public ConversationContext loadContext(String conversationId, String username) {
        String owner = normalizeUsername(username);
        if (conversationId == null || conversationId.isBlank()) {
            return new ConversationContext("", List.of());
        }

        ConversationSummary summary = properties.isEnabled()
                ? loadSummary(conversationId, owner)
                : null;
        List<org.springframework.ai.chat.messages.Message> messages =
                chatMemory.getForUser(conversationId, owner);
        int windowMessages = properties.getWindowTurns() * 2;
        int tailStart = Math.max(0, messages.size() - windowMessages);
        int summarizedMessages = summary == null || summary.getSummarizedMessageCount() == null
                ? 0 : Math.max(0, summary.getSummarizedMessageCount());
        // If compaction is still running, include the un-summarized prefix as
        // well so a request cannot lose context between the summary and tail.
        int start = summary == null ? tailStart : Math.min(tailStart, summarizedMessages);
        List<String> recent = formatRecentMessages(messages.subList(start, messages.size()));
        return new ConversationContext(
                summary == null || summary.getSummaryText() == null ? "" : summary.getSummaryText(), recent);
    }

    /** Schedules one coalesced background compaction task per conversation. */
    public void scheduleRefresh(String conversationId, String username) {
        if (!properties.isEnabled() || conversationId == null || conversationId.isBlank()) {
            return;
        }
        String owner = normalizeUsername(username);
        String pendingKey = pendingKey(conversationId, owner);
        String token = UUID.randomUUID().toString();
        Boolean accepted;
        try {
            accepted = redisTemplate.opsForValue().setIfAbsent(
                    pendingKey, token, pendingTtlSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("无法创建会话摘要待处理标记，跳过本轮异步更新: {}", e.getMessage());
            return;
        }
        if (!Boolean.TRUE.equals(accepted)) {
            return;
        }

        try {
            summaryExecutor.execute(() -> {
                try {
                    refresh(conversationId, owner);
                } catch (Exception e) {
                    log.warn("会话摘要更新失败: conversationId={}, username={}, error={}",
                            conversationId, owner, e.getMessage());
                } finally {
                    deleteIfOwned(pendingKey, token);
                }
            });
        } catch (RejectedExecutionException e) {
            deleteIfOwned(pendingKey, token);
            log.warn("会话摘要任务队列已满，稍后由下一轮消息触发: conversationId={}, username={}",
                    conversationId, owner);
        }
    }

    /** Deletes both cache and durable summary when a conversation is removed. */
    public void delete(String conversationId, String username) {
        String owner = normalizeUsername(username);
        try {
            redisTemplate.delete(summaryKey(conversationId, owner));
        } catch (Exception e) {
            log.debug("删除 Redis 会话摘要失败: {}", e.getMessage());
        }
        try {
            summaryMapper.deleteByConversationId(conversationId, owner);
        } catch (Exception e) {
            log.warn("删除 MySQL 会话摘要失败: conversationId={}, username={}, error={}",
                    conversationId, owner, e.getMessage());
        }
    }

    private void refresh(String conversationId, String owner) {
        String lockKey = lockKey(conversationId, owner);
        String lockToken = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey, lockToken, Math.max(30, properties.getLockTtlSeconds()), TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("会话摘要任务正在其他实例执行: conversationId={}, username={}", conversationId, owner);
            return;
        }

        try {
            ConversationSummary existing = loadSummary(conversationId, owner);
            List<org.springframework.ai.chat.messages.Message> messages =
                    chatMemory.getForUser(conversationId, owner);
            int totalTurns = messages.size() / 2;
            int summarizedMessages = existing == null || existing.getSummarizedMessageCount() == null
                    ? 0 : Math.max(0, existing.getSummarizedMessageCount());
            int summarizedTurns = summarizedMessages / 2;

            if (totalTurns <= properties.getWindowTurns()
                    || (existing == null && totalTurns < properties.getTriggerTurns())
                    || (existing != null && totalTurns - summarizedTurns <= properties.getWindowTurns())) {
                return;
            }

            int cutoffMessages = Math.max(0, (totalTurns - properties.getWindowTurns()) * 2);
            if (cutoffMessages <= summarizedMessages || cutoffMessages > messages.size()) {
                return;
            }

            List<org.springframework.ai.chat.messages.Message> delta =
                    messages.subList(summarizedMessages, cutoffMessages);
            String generated = generateSummary(existing == null ? "" : existing.getSummaryText(), delta);
            if (generated.isBlank()) {
                return;
            }

            ConversationSummary candidate = new ConversationSummary();
            candidate.setConversationId(conversationId);
            candidate.setUsername(owner);
            candidate.setSummaryText(truncate(generated));
            candidate.setSummarizedMessageCount(cutoffMessages);
            candidate.setVersion(existing == null || existing.getVersion() == null ? 0L : existing.getVersion());

            if (!persist(candidate, existing)) {
                return;
            }
            cacheSummary(candidate, existing == null ? 0L : candidate.getVersion() + 1);
        } finally {
            deleteIfOwned(lockKey, lockToken);
        }
    }

    private ConversationSummary loadSummary(String conversationId, String owner) {
        String key = summaryKey(conversationId, owner);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                ConversationSummary cached = objectMapper.readValue(json, ConversationSummary.class);
                if (cached.getSummaryText() != null && !cached.getSummaryText().isBlank()) {
                    return cached;
                }
            }
        } catch (Exception e) {
            log.debug("读取 Redis 会话摘要失败，回源 MySQL: {}", e.getMessage());
        }

        try {
            ConversationSummary durable = summaryMapper.selectByConversationId(conversationId, owner);
            if (durable != null && durable.getSummaryText() != null && !durable.getSummaryText().isBlank()) {
                cacheSummary(durable, durable.getVersion() == null ? 0L : durable.getVersion());
            }
            return durable;
        } catch (Exception e) {
            // The table is installed manually in this project. A missing or
            // temporarily unavailable table must not break the chat response.
            log.warn("读取 MySQL 会话摘要失败，继续使用最近消息: conversationId={}, username={}, error={}",
                    conversationId, owner, e.getMessage());
            return null;
        }
    }

    private String generateSummary(String previousSummary,
                                   List<org.springframework.ai.chat.messages.Message> delta) {
        String previous = previousSummary == null ? "" : previousSummary.trim();
        String historicalMessages = formatMessages(delta);
        String prompt = (previous.isBlank() ? "" : "已有长期摘要:\n" + previous + "\n\n")
                + "需要纳入摘要的历史消息:\n" + historicalMessages;
        return modelService.callDocument(SUMMARY_SYSTEM_PROMPT, prompt, false);
    }

    private boolean persist(ConversationSummary candidate, ConversationSummary existing) {
        try {
            if (existing == null) {
                return summaryMapper.insert(candidate) == 1;
            }
            return summaryMapper.updateIfVersionMatches(candidate) == 1;
        } catch (Exception e) {
            log.warn("写入 MySQL 会话摘要失败，保留旧摘要: conversationId={}, username={}, error={}",
                    candidate.getConversationId(), candidate.getUsername(), e.getMessage());
            return false;
        }
    }

    private void cacheSummary(ConversationSummary summary, long version) {
        try {
            summary.setVersion(version);
            redisTemplate.opsForValue().set(
                    summaryKey(summary.getConversationId(), summary.getUsername()),
                    objectMapper.writeValueAsString(summary),
                    Math.max(1, properties.getRedisTtlHours()), TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("写入 Redis 会话摘要失败，MySQL 摘要仍然有效: {}", e.getMessage());
        }
    }

    private List<String> formatRecentMessages(List<org.springframework.ai.chat.messages.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(this::formatMessage)
                .filter(text -> !text.isBlank())
                .collect(Collectors.toList());
    }

    private String formatMessages(List<org.springframework.ai.chat.messages.Message> messages) {
        return formatRecentMessages(messages).stream().collect(Collectors.joining("\n"));
    }

    private String formatMessage(org.springframework.ai.chat.messages.Message message) {
        String text = message.getText();
        return text == null || text.isBlank() ? "" : message.getMessageType().name() + ": " + text.trim();
    }

    private String truncate(String text) {
        String normalized = text == null ? "" : text.trim();
        return normalized.length() <= properties.getMaxSummaryChars()
                ? normalized
                : normalized.substring(0, properties.getMaxSummaryChars());
    }

    private String normalizeUsername(String username) {
        return username == null || username.isBlank() ? "anonymous" : username;
    }

    private String summaryKey(String conversationId, String owner) {
        return SUMMARY_KEY_PREFIX + owner + ":" + conversationId;
    }

    private String pendingKey(String conversationId, String owner) {
        return PENDING_KEY_PREFIX + owner + ":" + conversationId;
    }

    private String lockKey(String conversationId, String owner) {
        return LOCK_KEY_PREFIX + owner + ":" + conversationId;
    }

    private long pendingTtlSeconds() {
        return Math.max(60, properties.getLockTtlSeconds() * 2);
    }

    private void deleteIfOwned(String key, String token) {
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then "
                + "return redis.call('del', KEYS[1]) else return 0 end";
        try {
            redisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
                    Collections.singletonList(key), token);
        } catch (Exception e) {
            log.debug("释放会话摘要 Redis 锁失败: {}", e.getMessage());
        }
    }

    private void validateProperties() {
        if (properties.getWindowTurns() < 1
                || properties.getTriggerTurns() <= properties.getWindowTurns()
                || properties.getMaxSummaryChars() < 1
                || properties.getRedisTtlHours() < 1
                || properties.getLockTtlSeconds() < 1) {
            throw new IllegalArgumentException("会话摘要参数非法");
        }
    }

    public record ConversationContext(String summary, List<String> recentMessages) {

        public ConversationContext {
            summary = summary == null ? "" : summary;
            recentMessages = recentMessages == null ? List.of() : List.copyOf(new ArrayList<>(recentMessages));
        }
    }
}
