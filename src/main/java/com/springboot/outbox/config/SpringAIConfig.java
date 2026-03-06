package com.springboot.outbox.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class SpringAIConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        log.info("Initializing ChatClient with Ollama model");
        return ChatClient.builder(chatModel).build();
    }
}
