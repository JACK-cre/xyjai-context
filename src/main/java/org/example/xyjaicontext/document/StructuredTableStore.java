package org.example.xyjaicontext.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.xyjaicontext.document.model.DocumentElement;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StructuredTableStore {

    private static final String KEY_PREFIX = "doc:tables:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void replace(String taskId, String username, List<DocumentElement> tables) {
        String key = key(taskId);
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (DocumentElement table : tables) {
                String tableId = table.tableId().isBlank() ? table.id() : table.tableId();
                values.put(tableId, objectMapper.writeValueAsString(
                        new StoredTable(username, table)));
            }
            redisTemplate.delete(key);
            if (!values.isEmpty()) {
                redisTemplate.opsForHash().putAll(key, values);
            }
        } catch (Exception e) {
            throw new IllegalStateException("结构化表格保存失败", e);
        }
    }

    public DocumentElement get(String taskId, String tableId, String username) {
        if (taskId == null || tableId == null || username == null) {
            return null;
        }
        try {
            Object json = redisTemplate.opsForHash().get(key(taskId), tableId);
            if (json == null) {
                return null;
            }
            StoredTable stored = objectMapper.readValue(String.valueOf(json), StoredTable.class);
            return username.equals(stored.username()) ? stored.table() : null;
        } catch (Exception e) {
            log.warn("结构化表格读取失败: taskId={}, tableId={}, error={}",
                    taskId, tableId, e.getMessage());
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

    private record StoredTable(String username, DocumentElement table) {
    }
}
