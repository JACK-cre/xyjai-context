package org.example.xyjaicontext.controller;

import lombok.RequiredArgsConstructor;
import org.example.xyjaicontext.dto.ApiResponse;
import org.example.xyjaicontext.memory.RedisChatMemory;
import org.example.xyjaicontext.model.ConversationRecord;
import org.example.xyjaicontext.service.ChatService;
import org.example.xyjaicontext.service.DocumentService;
import org.example.xyjaicontext.service.AgentWorkflowService;
import org.example.xyjaicontext.service.ConversationSummaryService;
import org.example.xyjaicontext.service.ModelCallException;
import org.example.xyjaicontext.service.ModelOverloadedException;
import org.example.xyjaicontext.service.ModelTimeoutException;
import org.example.xyjaicontext.util.RedissonRateLimiter;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final ChatService chatService;
    private final AgentWorkflowService agentWorkflowService;
    private final ConversationSummaryService conversationSummaryService;
    private final DocumentService documentService;
    private final RedissonRateLimiter rateLimiter;
    private final RedisChatMemory chatMemory;
    private final StringRedisTemplate redisTemplate;
    @Value("${rate-limit.chat.max-requests}")
    private int maxRequests;
    @Value("${rate-limit.chat.window-seconds}")
    private int windowSeconds;
    // 获取用户历史对话列表
    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<List<ConversationRecord>>> getConversations(Authentication authentication) {
        String username = authentication.getName();
        try {
            List<ConversationRecord> conversations = chatMemory.getUserConversations(username);
            return ResponseEntity.ok(ApiResponse.success(conversations));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.success(List.of()));
        }
    }
    // 获取具体对话的历史记录
    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<List<Message>>> getConversationHistory(@PathVariable String conversationId,
                                                                              Authentication authentication) {
        try {
            List<Message> messages = chatMemory.getForUser(conversationId, authentication.getName());
            return ResponseEntity.ok(ApiResponse.success(messages));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.success(List.of()));
        }
    }
    // 删除对话
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteConversation(@PathVariable String conversationId,
                                                                                Authentication authentication) {
        try {
            chatMemory.deleteUserConversation(conversationId, authentication.getName());
            conversationSummaryService.delete(conversationId, authentication.getName());
            return ResponseEntity.ok(ApiResponse.success(Map.of("msg", "对话删除成功")));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.success(Map.of("error", "删除失败: " + e.getMessage())));
        }
    }
    // ✅ 修正：返回类型改为 Map，兼容成功时的多字段响应
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chat(@RequestParam String question,
                                                                 @RequestParam(defaultValue = "false") boolean useRag,
                                                                 @RequestParam(required = false) String conversationId,
                                                                 Authentication authentication) {
        String convId = conversationId != null ? conversationId : UUID.randomUUID().toString();
        String username = authentication.getName();
        String clientIp = "user:" + username;

        if (!rateLimiter.isAllowed(clientIp, maxRequests, windowSeconds)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.success(Map.of("error", "请求过于频繁，请稍后再试")));
        }

        try {
            AgentWorkflowService.ChatResult result = chatService.chatWithMetadata(question, useRag, convId, username);
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("answer", result.answer());
            response.put("conversationId", convId);
            response.put("username", username);
            if (result.runId() != null) {
                response.put("runId", result.runId());
            }
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (ModelOverloadedException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.success(Map.of("code", "MODEL_OVERLOADED", "error", e.getMessage())));
        } catch (ModelTimeoutException e) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ApiResponse.success(Map.of("code", "MODEL_TIMEOUT", "error", e.getMessage())));
        } catch (ModelCallException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.success(Map.of("code", "MODEL_UNAVAILABLE", "error", e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.success(Map.of("error", "AI服务异常: " + e.getMessage())));
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(@RequestParam("file") MultipartFile file,
                                                                   Authentication authentication) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.success(Map.of("error", "文件不能为空")));
        }
        try {
            String taskId = documentService.processDocumentAsync(file, authentication.getName());
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "msg", "文档已提交解析，后台异步处理中",
                    "taskId", taskId,
                    "status", "PROCESSING",
                    "username", authentication.getName()
            )));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.success(Map.of("error", "上传失败: " + e.getMessage())));
        }
    }

    // 获取文档处理状态
    @GetMapping("/document/status/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDocumentStatus(@PathVariable String taskId,
                                                                                Authentication authentication) {
        try {
            String owner = redisTemplate.opsForValue().get("doc:owner:" + taskId);
            if (owner == null || !owner.equals(authentication.getName())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.success(Map.of("status", "NOT_FOUND")));
            }
            String statusKey = "doc:status:" + taskId;
            String status = redisTemplate.opsForValue().get(statusKey);
            if (status == null) {
                return ResponseEntity.ok(ApiResponse.success(Map.of("status", "NOT_FOUND")));
            }
            return ResponseEntity.ok(ApiResponse.success(Map.of("status", status)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.success(Map.of("error", "获取状态失败: " + e.getMessage())));
        }
    }

    @GetMapping("/document/result/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDocumentResult(@PathVariable String taskId,
                                                                                Authentication authentication) {
        try {
            Map<String, Object> result = documentService.getResult(taskId, authentication.getName());
            if (result == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.success(Map.of("status", "NOT_FOUND")));
            }
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.success(Map.of("error", "获取文档结果失败: " + e.getMessage())));
        }
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAgentRun(@PathVariable String runId,
                                                                          Authentication authentication) {
        Map<String, Object> run = agentWorkflowService.getRun(runId, authentication.getName());
        if (run == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.success(Map.of("status", "NOT_FOUND")));
        }
        return ResponseEntity.ok(ApiResponse.success(run));
    }
}
