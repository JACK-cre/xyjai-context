package org.example.xyjaicontext.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ConversationRecord {
    private Long id;
    private String conversationId;
    private String username;
    private String messages;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
