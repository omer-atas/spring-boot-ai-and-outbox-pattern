package com.springboot.outbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "outbox")
@Validated
public class OutboxProperties {

    @NotNull
    private SchedulerConfig scheduler = new SchedulerConfig();

    @NotNull
    private CleanupConfig cleanup = new CleanupConfig();

    @Data
    public static class SchedulerConfig {

        private boolean enabled = true;

        @Min(1000)
        private long pendingFixedDelay = 5000;

        @Min(1000)
        private long retryFixedDelay = 60000;
    }

    @Data
    public static class CleanupConfig {

        private boolean enabled = true;

        @Min(1)
        private int retentionDays = 30;

        @Min(100)
        private int batchSize = 5000;
    }
}
