package com.example.chatbot.claude;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(ClaudeProperties.class)
public class ClaudeConfig {

    /** Single reusable HttpClient for talking to the local Ollama server. */
    @Bean
    public HttpClient ollamaHttpClient(ClaudeProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                .build();
    }
}
