package org.example.xyjaicontext.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.xyjaicontext.document.model.DocumentElement;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredTableStoreTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void keepsRawRowsAndEnforcesOwnership() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations hashOperations = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOperations);
        StructuredTableStore store = new StructuredTableStore(redis, new ObjectMapper());
        DocumentElement table = DocumentElement.table("element-1", "table text", "退款规则",
                2, "", "refund-table", List.of(
                        List.of("订单类型", "退款时间"), List.of("跨境订单", "15天")));

        store.replace("task-1", "alice", List.of(table));

        ArgumentCaptor<Map> values = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("doc:tables:task-1"), values.capture());
        when(hashOperations.get("doc:tables:task-1", "refund-table"))
                .thenReturn(values.getValue().get("refund-table"));

        assertThat(store.get("task-1", "refund-table", "alice")).isEqualTo(table);
        assertThat(store.get("task-1", "refund-table", "bob")).isNull();
    }
}
