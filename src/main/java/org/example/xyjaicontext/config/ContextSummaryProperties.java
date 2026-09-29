package org.example.xyjaicontext.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Conversation-summary compaction settings. */
@Data
@Component
@ConfigurationProperties(prefix = "agent.context.summary")
public class ContextSummaryProperties {

    private boolean enabled = true;
    private int windowTurns = 4;
    private int triggerTurns = 6;
    private int maxSummaryChars = 3000;
    private long redisTtlHours = 168;
    private long lockTtlSeconds = 120;
}
