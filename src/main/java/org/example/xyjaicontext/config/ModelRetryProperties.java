package org.example.xyjaicontext.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Bounded retry budgets for interactive and document model calls. */
@Data
@Component
@ConfigurationProperties(prefix = "agent.model.retry")
public class ModelRetryProperties {

    private Policy chat = new Policy(
            2, Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(70));

    private Policy document = new Policy(
            2, Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(180));

    @Data
    public static class Policy {

        private int maxAttempts;
        private Duration initialBackoff;
        private Duration maxBackoff;
        private Duration totalTimeout;

        public Policy() {
        }

        public Policy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Duration totalTimeout) {
            this.maxAttempts = maxAttempts;
            this.initialBackoff = initialBackoff;
            this.maxBackoff = maxBackoff;
            this.totalTimeout = totalTimeout;
        }
    }
}
