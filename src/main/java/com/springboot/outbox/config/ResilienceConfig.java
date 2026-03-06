package com.springboot.outbox.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class ResilienceConfig {

    /**
     * Circuit Breaker Configuration
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config
                = CircuitBreakerConfig.custom()
                        .slidingWindowSize(20)
                        .minimumNumberOfCalls(10)
                        .failureRateThreshold(50.0f)
                        .slowCallRateThreshold(50.0f)
                        .slowCallDurationThreshold(Duration.ofSeconds(2))
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(5)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .recordExceptions(Exception.class)
                        .build();
        log.info("Circuit Breaker configured: failureRateThreshold=50%, waitDuration=30s");
        return CircuitBreakerRegistry.of(config);
    }

    /**
     * Retry Configuration
     */
    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig config
                = RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofSeconds(1))
                        .retryExceptions(Exception.class)
                        .build();
        log.info("Retry configured: maxAttempts=3, waitDuration=1s");
        return RetryRegistry.of(config);
    }

    /**
     * Time Limiter Configuration
     */
    @Bean
    public TimeLimiterRegistry timeLimiterRegistry() {
        TimeLimiterConfig config
                = TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(30))
                        .cancelRunningFuture(true)
                        .build();
        log.info("Time Limiter configured: timeout=30s");
        return TimeLimiterRegistry.of(config);
    }
}
