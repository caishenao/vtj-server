package cn.cai.vtjserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RedisCacheService {
    private final Optional<StringRedisTemplate> redisTemplate;

    public void put(String key, String value, Duration ttl) {
        redisTemplate.ifPresent(redis -> {
            try {
                redis.opsForValue().set(key, value, ttl);
            } catch (Exception ignored) {
                // Redis is an acceleration layer here. Postgres remains authoritative.
            }
        });
    }

    public Optional<String> get(String key) {
        if (redisTemplate.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(redisTemplate.get().opsForValue().get(key));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public void delete(String key) {
        redisTemplate.ifPresent(redis -> {
            try {
                redis.delete(key);
            } catch (Exception ignored) {
            }
        });
    }
}
