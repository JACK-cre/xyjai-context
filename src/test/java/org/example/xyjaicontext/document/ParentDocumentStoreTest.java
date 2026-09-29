package org.example.xyjaicontext.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.xyjaicontext.document.model.DocumentChunk;
import org.example.xyjaicontext.document.model.DocumentElementType;
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

class ParentDocumentStoreTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void storesAndReadsOnlyForTheOwningUser() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations hashOperations = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOperations);
        ParentDocumentStore store = new ParentDocumentStore(redis, new ObjectMapper());
        DocumentChunk parent = new DocumentChunk("parent-1", "", "正文", "来源: rules.docx\n正文",
                "退款规则", DocumentElementType.PARAGRAPH, 1, "", "", null, null);

        store.replace("task-1", "alice", List.of(parent));

        ArgumentCaptor<Map> values = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("doc:parents:task-1"), values.capture());
        when(hashOperations.get("doc:parents:task-1", "parent-1"))
                .thenReturn(values.getValue().get("parent-1"));

        assertThat(store.get("task-1", "parent-1", "alice")).isEqualTo(parent);
        assertThat(store.get("task-1", "parent-1", "bob")).isNull();
    }
}
