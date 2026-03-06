package com.springboot.outbox.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Spring Boot Outbox Pattern API")
                                .description(
                                        """
                    Enterprise-grade Transactional Outbox Pattern implementation for reliable event delivery.
                    ## Features
                    - ACID transaction guarantees
                    - Automatic retry with exponential backoff
                    - Dead letter queue for failed events
                    - Comprehensive monitoring and metrics
                    ## Authentication
                    All endpoints require JWT authentication.
                    """)
                                .version("1.0.0")
                                .contact(
                                        new Contact()
                                                .name("Spring Boot Outbox Team")
                                                .email("support@example.com")
                                                .url("https://docs.example.com"))
                                .license(
                                        new License()
                                                .name("Apache 2.0")
                                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")))
                .servers(
                        List.of(
                                new Server().url("http://localhost:8080").description("Local Development"),
                                new Server()
                                        .url("https://api-staging.example.com/outbox/v1")
                                        .description("Staging"),
                                new Server().url("https://api.example.com/outbox/v1").description("Production")))
                .tags(
                        List.of(
                                new Tag().name("Transaction API").description("Transaction management endpoints"),
                                new Tag().name("Outbox API").description("Outbox event management endpoints"),
                                new Tag().name("Analytics API").description("AI-powered analytics and insights"),
                                new Tag().name("Admin").description("Administrative endpoints"),
                                new Tag().name("Monitoring").description("Health and metrics endpoints")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description("JWT token authentication")));
    }
}
