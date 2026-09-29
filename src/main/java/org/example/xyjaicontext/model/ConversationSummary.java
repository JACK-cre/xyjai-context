package org.example.xyjaicontext.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Durable summary metadata for one tenant-scoped conversation. */
@Data
public class ConversationSummary {

    private Long id;
    private String conversationId;
    private String username;
    private String summaryText;
    private Integer summarizedMessageCount;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
