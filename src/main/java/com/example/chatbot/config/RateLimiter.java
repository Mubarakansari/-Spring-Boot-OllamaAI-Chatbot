package com.example.chatbot.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Simple fixed-window rate limiter backed by Redis INCR + EXPIRE.
 * Good enough for a per-user chat endpoint; swap for a token-bucket
 * algorithm (e.g. Bucket4j) if you need smoother limiting under bursty load.
 */
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${ratelimit.requests-per-minute:20}")
    private int requestsPerMinute;

    /** Returns true if the request is allowed, false if the caller is over their limit. */
    public boolean allow(String userKey) {
        String key = "ratelimit:" + userKey + ":" + (System.currentTimeMillis() / 60_000);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofMinutes(1));
        }
        return count == null || count <= requestsPerMinute;
    }
}
