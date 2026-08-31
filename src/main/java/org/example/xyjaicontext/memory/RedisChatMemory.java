package org.example.xyjaicontext.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.xyjaicontext.mapper.ConversationMapper;
import org.example.xyjaicontext.model.ConversationRecord;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.example.xyjaicontext.memory.UserContext;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemory implements ChatMemory {

    private final CacheManager cacheManager;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationMapper conversationMapper;

    // 1. Key 前缀定义
    private static final String MEMORY_KEY_PREFIX = "chat:memory:";
    private static final String LOCK_KEY_PREFIX = "chat:lock:";
    // 索引 Key，用于存储当前分段数量
    private static final String SEGMENT_INDEX_SUFFIX = ":index";
    // 分段数据 Key 后缀
    private static final String SEGMENT_DATA_SUFFIX = ":seg:";

    private static final long TTL_HOURS = 24;
    private static final long LOCK_EXPIRE_SECONDS = 30;

    // 2. 每个分段存储的最大消息数 (控制单个 Value 大小的关键配置)
    // 根据你的消息平均大小调整，建议 50-100 条一个分段，避免单个 Value 过大
    private static final int MESSAGES_PER_SEGMENT = 50;

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        String username = UserContext.getUsername();
        if (username == null) username = "anonymous";
        String baseKey = MEMORY_KEY_PREFIX + conversationId;

        // 1. 获取分布式锁
        String requestId = acquireLock(conversationId);
        if (requestId == null) {
            log.warn("Failed to acquire lock for conversation: {}", conversationId);
            return;
        }
        try {
            // 2. 【读取旧数据】为了合并新旧消息以更新数据库，我们需要先读取当前的全量数据
            // 注意：这里为了更新数据库，我们不得不读取一次（或者你可以在 DB 层做增量 Append）
            List<Message> existingMessages = get(conversationId); // 复用 get 方法（可能会触发 DB 回源）
            List<Message> updatedMessages = new ArrayList<>(existingMessages);
            updatedMessages.addAll(messages);

            // 3. 【核心】更新数据库
            String fullJsonForDb = objectMapper.writeValueAsString(updatedMessages);
            updateDatabaseWithUsername(conversationId, fullJsonForDb, username);
            // 7. 清除本地缓存
            Cache cache = cacheManager.getCache("chatMemory");
            if (cache != null) {
                cache.evict(conversationId);
            }
            // 4. 【关键步骤】物理删除 Redis 缓存
            // 我们需要删除索引 Key，这样下次 get 的时候会认为缓存失效
            String indexKey = baseKey + SEGMENT_INDEX_SUFFIX;

            // 获取当前的索引值，以便删除具体的分段 Key
            String currentIndexStr = redisTemplate.opsForValue().get(indexKey);
            Set<String> keysToDelete = new HashSet<>();
            keysToDelete.add(indexKey); // 加入索引 Key

            if (currentIndexStr != null) {
                Long maxIndex = Long.valueOf(currentIndexStr);
                // 将所有可能的分段 Key 加入删除列表
                for (long i = 1; i <= maxIndex; i++) {
                    keysToDelete.add(baseKey + SEGMENT_DATA_SUFFIX + i);
                }
            }

            // 执行物理删除
            if (!keysToDelete.isEmpty()) {
                redisTemplate.delete(keysToDelete);
                log.info("Add 操作：已物理删除 Redis 中的 {} 个缓存 Key", keysToDelete.size());
            }

            // 注意：这里不再写入 Redis 分段，由下次 get 操作来重建

        } catch (Exception e) {
            log.error("Failed to add messages for conversation: {}", conversationId, e);
            throw new RuntimeException("Chat memory write failed", e);
        } finally {
            releaseLock(conversationId, requestId);
        }
    }

    @Override
    @Cacheable(value = "chatMemory", key = "#conversationId")
    public List<Message> get(String conversationId) {
        String baseKey = MEMORY_KEY_PREFIX + conversationId;
        String indexKey = baseKey + SEGMENT_INDEX_SUFFIX;

        try {
            // 1. 检查 Redis 中是否有缓存（通过索引 Key）
            String indexStr = redisTemplate.opsForValue().get(indexKey);

            if (indexStr != null) {
                // 缓存命中：读取所有分段
                log.debug("Cache HIT for conversation: {}", conversationId);
                return getAllMessagesFromRedis(baseKey, Long.valueOf(indexStr));
            } else {
                // 缓存未命中：需要从数据库加载并重建缓存
                log.debug("Cache MISS for conversation: {}. 正在从数据库重建缓存...", conversationId);
                return loadAndRebuildCache(conversationId, baseKey);
            }

        } catch (Exception e) {
            log.error("Error reading memory, falling back to DB: {}", conversationId, e);
            // 异常时回源数据库，但不写回 Redis 防止脏数据
            return getFromDatabase(conversationId);
        }
    }

    /**
     * 从数据库加载数据，并按分段策略写入 Redis
     */
    private List<Message> loadAndRebuildCache(String conversationId, String baseKey) {
        // 1. 从数据库获取全量数据
        List<Message> allMessages = getFromDatabase(conversationId);

        if (allMessages == null || allMessages.isEmpty()) {
            return List.of();
        }

        // 2. 获取锁，防止多个线程同时重建缓存（缓存击穿）
        String requestId = acquireLock(conversationId);
        if (requestId == null) {
            log.warn("重建缓存时获取锁失败，直接返回 DB 数据");
            return allMessages;
        }

        try {
            // 3. 双重检查（Double Check），防止在获取锁的过程中其他线程已经重建好了
            String indexKey = baseKey + SEGMENT_INDEX_SUFFIX;
            String currentIndex = redisTemplate.opsForValue().get(indexKey);
            if (currentIndex != null) {
                // 如果已经有其他线程重建了，直接读取
                return getAllMessagesFromRedis(baseKey, Long.valueOf(currentIndex));
            }

            // 4. 【核心】执行分段写入逻辑
            // 清理可能存在的脏数据（可选）
            String indexKeyTemp = baseKey + SEGMENT_INDEX_SUFFIX;
            redisTemplate.delete(indexKeyTemp); // 删除旧索引

            // 计算分段数量
            int totalSize = allMessages.size();
            int segmentCount = (int) Math.ceil((double) totalSize / MESSAGES_PER_SEGMENT);

            // 写入分段
            for (int i = 1; i <= segmentCount; i++) {
                int start = (i - 1) * MESSAGES_PER_SEGMENT;
                int end = Math.min(start + MESSAGES_PER_SEGMENT, totalSize);
                List<Message> subList = allMessages.subList(start, end);

                String segmentKey = baseKey + SEGMENT_DATA_SUFFIX + i;
                String json = objectMapper.writeValueAsString(subList);
                redisTemplate.opsForValue().set(segmentKey, json, TTL_HOURS, TimeUnit.HOURS);
            }

            // 写入索引
            redisTemplate.opsForValue().set(indexKeyTemp, String.valueOf(segmentCount), TTL_HOURS, TimeUnit.HOURS);

            log.info("缓存重建完成：对话 {} 被切分为 {} 个分段", conversationId, segmentCount);
            return allMessages;

        } catch (Exception e) {
            log.error("缓存重建失败：{}", conversationId, e);
            // 失败则直接返回 DB 数据，不抛异常
            return allMessages;
        } finally {
            releaseLock(conversationId, requestId);
        }
    }
    @Override
    @CacheEvict(value = "chatMemory", key = "#conversationId")
    public void clear(String conversationId) {
        String baseKey = MEMORY_KEY_PREFIX + conversationId;
        String requestId = acquireLock(conversationId);
        if (requestId == null) return;

        try {
            // 1. 获取索引
            String indexKey = baseKey + SEGMENT_INDEX_SUFFIX;
            String indexStr = redisTemplate.opsForValue().get(indexKey);

            if (indexStr != null) {
                Long maxIndex = Long.valueOf(indexStr);
                // 2. 删除所有分段 Key
                List<String> keysToDelete = new ArrayList<>();
                keysToDelete.add(indexKey);
                for (long i = 1; i <= maxIndex; i++) {
                    keysToDelete.add(baseKey + SEGMENT_DATA_SUFFIX + i);
                }
                redisTemplate.delete(keysToDelete);
            }

            // 3. 删除数据库记录
            conversationMapper.deleteByConversationId(conversationId);

        } finally {
            releaseLock(conversationId, requestId);
        }
    }

    // ==================== 辅助方法 ====================

    // 从 Redis 读取所有分段并合并 (用于同步更新数据库)
    private List<Message> getAllMessagesFromRedis(String baseKey, Long currentMaxIndex) {
        List<Message> all = new ArrayList<>();
        for (long i = 1; i <= currentMaxIndex; i++) {
            String key = baseKey + SEGMENT_DATA_SUFFIX + i;
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                try {
                    List<Message> part = objectMapper.readValue(json, new TypeReference<List<Message>>() {});
                    all.addAll(part);
                } catch (Exception e) {
                    log.warn("Error parsing segment: {}", key, e);
                }
            }
        }
        return all;
    }

    // 从数据库获取 (保持不变)
    private List<Message> getFromDatabase(String conversationId) {
        ConversationRecord record = conversationMapper.selectByConversationId(conversationId, UserContext.getUsername());
        if (record == null || record.getMessages() == null) {
            return List.of();
        }
        try {
            List<Message> messages = objectMapper.readValue(record.getMessages(), new TypeReference<List<Message>>() {});
            // 异步回种 Redis (可选，热加载)
            // asyncReloadToRedis(conversationId, messages);
            return messages;
        } catch (Exception e) {
            return List.of();
        }
    }

    // 更新数据库 (保持不变)
    private void updateDatabaseWithUsername(String conversationId, String messagesJson, String username) {
        ConversationRecord record = conversationMapper.selectByConversationId(conversationId, username);
        if (record == null) {
            record = new ConversationRecord();
            record.setConversationId(conversationId);
            record.setUsername(username);
            record.setMessages(messagesJson);
            conversationMapper.insert(record);
        } else {
            record.setUsername(username);
            record.setMessages(messagesJson);
            conversationMapper.update(record);
        }
    }
    // 获取用户对话列表
    @Cacheable(value = "userConversations", key = "#username")
    public List<ConversationRecord> getUserConversations(String username) {
        return conversationMapper.selectByUsername(username);
    }

    // 删除用户对话
    @CacheEvict(value = {"chatMemory", "userConversations"}, key = "#conversationId")
    public void deleteUserConversation(String conversationId) {
        clear(conversationId);
    }
    // ... (保留原有的 acquireLock, releaseLock, addConversation 等方法)

    // 示例：简单的锁获取方法
    private String acquireLock(String conversationId) {
        String lockKey = LOCK_KEY_PREFIX + conversationId + UserContext.getUsername();
        String requestId = UUID.randomUUID().toString();
        Boolean success = redisTemplate.opsForValue().setIfAbsent(lockKey, requestId, LOCK_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return success != null && success ? requestId : null;
    }

    private void releaseLock(String conversationId, String requestId) {
        if (requestId == null) return;
        String lockKey = LOCK_KEY_PREFIX + conversationId + UserContext.getUsername();
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        redisTemplate.execute(new DefaultRedisScript<>(script, Long.class), Collections.singletonList(lockKey), requestId);
    }

    // 保存对话记录，关联用户
    @CacheEvict(value = "userConversations", key = "#username")
    public void addConversation(String username, String conversationId, String title) {
        // 从缓存获取最新的消息
        List<Message> messages = get(conversationId);
        try {
            String messagesJson = objectMapper.writeValueAsString(messages);
            ConversationRecord record = conversationMapper.selectByConversationId(conversationId, username);
            if (record == null) {
                // 新增记录
                record = new ConversationRecord();
                record.setConversationId(conversationId);
                record.setUsername(username);
                record.setMessages(messagesJson);
                conversationMapper.insert(record);
            } else {
                // 更新记录
                record.setUsername(username);
                record.setMessages(messagesJson);
                conversationMapper.update(record);
            }
        } catch (Exception e) {
            log.error("Failed to save conversation", e);
            throw new RuntimeException("Failed to save conversation", e);
        }
    }

}