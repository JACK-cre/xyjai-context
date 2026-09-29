package org.example.xyjaicontext.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.xyjaicontext.document.model.DocumentChunk;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParentDocumentStore {

    private static final String KEY_PREFIX = "doc:parents:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void replace(String taskId, String username, List<DocumentChunk> parents) {
        String key = key(taskId);
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (DocumentChunk parent : parents) {
                values.put(parent.chunkId(), objectMapper.writeValueAsString(
                        new StoredParent(username, parent)));
            }
            redisTemplate.delete(key);
            if (!values.isEmpty()) {
                redisTemplate.opsForHash().putAll(key, values);
            }
        } catch (Exception e) {
            throw new IllegalStateException("父级文档保存失败", e);
        }
    }

    public DocumentChunk get(String taskId, String parentId, String username) {
        if (taskId == null || parentId == null || username == null) {
            return null;
        }
        try {
            Object json = redisTemplate.opsForHash().get(key(taskId), parentId);
            if (json == null) {
                return null;
            }
            StoredParent stored = objectMapper.readValue(String.valueOf(json), StoredParent.class);
            return username.equals(stored.username()) ? stored.chunk() : null;
        } catch (Exception e) {
            log.warn("父级文档读取失败: taskId={}, parentId={}, error={}",
                    taskId, parentId, e.getMessage());
            return null;
        }
    }

    public void delete(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            redisTemplate.delete(key(taskId));
        }
    }

    private String key(String taskId) {
        return KEY_PREFIX + taskId;
    }

    private record StoredParent(String username, DocumentChunk chunk) {
    }
}
