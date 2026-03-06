package com.springboot.outbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
@EnableTransactionManagement
@EnableJpaAuditing
@EnableScheduling
@EnableAsync
@EnableCaching
@EnableConfigurationProperties
@OpenAPIDefinition(
        info
        = @Info(
                title = "Spring Boot Outbox Pattern API",
                version = "1.0.0",
                description
                = "Production-ready Outbox Pattern implementation with Spring Boot 3.2, Java 21, and"
                + " Spring AI",
                contact = @Contact(name = "Spring Boot Outbox Team", email = "support@example.com"),
                license
                = @License(name = "Apache 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")))
public class OutboxApplication {

    public static void main(String[] args) {
        log.info("Starting Spring Boot Outbox Pattern Application...");
        log.info("Java Version: {}", System.getProperty("java.version"));
        log.info(
                "Spring Boot Version: {}", SpringApplication.class.getPackage().getImplementationVersion());
        SpringApplication.run(OutboxApplication.class, args);
        log.info("Spring Boot Outbox Pattern Application started successfully!");
        log.info("Swagger UI: http://localhost:8080/swagger-ui.html");
        log.info("Actuator: http://localhost:8080/actuator");
        log.info("Prometheus Metrics: http://localhost:8080/actuator/prometheus");
    }
}
