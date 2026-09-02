package org.example.xyjaicontext.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.xyjaicontext.agent.GraphStateKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/** Runs the document parsing, analysis and indexing StateGraph. */
@Service
public class DocumentWorkflowService {

    private final CompiledGraph documentGraph;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public DocumentWorkflowService(@Qualifier("documentCompiledGraph") CompiledGraph documentGraph,
                                   StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper) {
        this.documentGraph = documentGraph;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> process(String taskId, String username, String fileName, byte[] fileContent) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(GraphStateKeys.TASK_ID, taskId);
        input.put(GraphStateKeys.USERNAME, username == null ? "anonymous" : username);
        input.put(GraphStateKeys.FILE_NAME, fileName == null ? "unknown" : fileName);
        if (fileContent == null || fileContent.length == 0) {
            throw new IllegalArgumentException("文档内容为空");
        }
        input.put(GraphStateKeys.FILE_CONTENT, fileContent);
        input.put(GraphStateKeys.CHECKSUM, sha256(fileContent));
        String threadId = (username == null ? "anonymous" : username) + ":" + taskId;
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        OverAllState state = documentGraph.invoke(input, config)
                .orElseThrow(() -> new IllegalStateException("文档工作流未返回结果"));
        return new LinkedHashMap<>(state.data());
    }

    public Map<String, Object> getResult(String taskId, String username) {
        String owner = redisTemplate.opsForValue().get("doc:owner:" + taskId);
        if (owner == null || !owner.equals(username)) {
            return null;
        }
        String json = redisTemplate.opsForValue().get("doc:result:" + taskId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("文档结果读取失败", e);
        }
    }

    private String sha256(byte[] content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content == null ? new byte[0] : content);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
