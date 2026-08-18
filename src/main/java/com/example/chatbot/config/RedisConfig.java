package com.example.chatbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * What goes in Redis vs the DB:
     *  - Redis: recently-used conversation context (trimmed message list) for fast
     *    reads on the "hot path" of sending a chat message; rate-limit counters;
     *    short-lived session/JWT blacklist entries.
     *  - Postgres: the durable source of truth for users, conversations, and
     *    the full message history. Redis entries are a cache and can be
     *    rebuilt from Postgres at any time - the app must degrade gracefully
     *    if Redis is unavailable (see GlobalExceptionHandler).
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory, ObjectMapper objectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
        return template;
    }
}
