package com.medicalagent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.concurrent.TimeUnit;

/**
 * Session Redis 持久化 (T1.4)
 */
@Repository
public class SessionRepository {

    private static final String KEY_PREFIX = "med:session:";
    private final ObjectMapper mapper;
    private final StringRedisTemplate redisTemplate;

    public SessionRepository(StringRedisTemplate redisTemplate, ObjectMapper mapper) {
        this.redisTemplate = redisTemplate;
        this.mapper = mapper;
    }

    /**
     * 保存 AgentState JSON，设置 TTL
     */
    public void save(String sessionId, Object state, long ttlMinutes) {
        try {
            String json = mapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + sessionId, json, ttlMinutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            // Redis 不可用时静默降级
        }
    }

    /**
     * 读取 AgentState JSON
     */
    public String load(String sessionId) {
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 删除 Session
     */
    public void delete(String sessionId) {
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception e) { /* ignore */ }
    }

    /**
     * 刷新 TTL
     */
    public boolean refreshTtl(String sessionId, long ttlMinutes) {
        try {
            return redisTemplate.expire(KEY_PREFIX + sessionId, ttlMinutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            return false;
        }
    }
}
