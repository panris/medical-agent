package com.medicalagent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.concurrent.TimeUnit;
import java.util.*;

/**
 * Session Redis 持久化 (T1.4)
 */
@Repository
public class SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionRepository.class);
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
            log.warn("Redis save failed, sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 读取 AgentState JSON
     */
    public String load(String sessionId) {
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.debug("Redis load failed, sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 删除 Session
     */
    public void delete(String sessionId) {
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.debug("Redis delete failed, sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 刷新 TTL
     */
    public boolean refreshTtl(String sessionId, long ttlMinutes) {
        try {
            return redisTemplate.expire(KEY_PREFIX + sessionId, ttlMinutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("Redis expire failed, sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    // === 会话元数据（用于侧边栏列表）===
    private static final String META_KEY = "med:sessions:index";

    /**
     * 保存会话元数据（首次消息 + 时间戳）
     */
    public void saveMetadata(String sessionId, String firstMessage) {
        try {
            Map<String, String> meta = new HashMap<>();
            meta.put("sessionId", sessionId);
            meta.put("firstMessage", firstMessage.length() > 50 ? firstMessage.substring(0, 50) + "..." : firstMessage);
            meta.put("createdAt", String.valueOf(System.currentTimeMillis()));
            meta.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            redisTemplate.opsForHash().put(META_KEY, sessionId, mapper.writeValueAsString(meta));
        } catch (Exception e) {
            log.debug("Redis saveMetadata failed: {}", e.getMessage());
        }
    }

    /**
     * 更新会话最后活跃时间
     */
    public void touchMetadata(String sessionId) {
        try {
            Object raw = redisTemplate.opsForHash().get(META_KEY, sessionId);
            if (raw != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = mapper.readValue((String) raw, Map.class);
                meta.put("updatedAt", String.valueOf(System.currentTimeMillis()));
                redisTemplate.opsForHash().put(META_KEY, sessionId, mapper.writeValueAsString(meta));
            }
        } catch (Exception e) {
            log.debug("Redis touchMetadata failed: {}", e.getMessage());
        }
    }

    /**
     * 删除会话元数据
     */
    public void deleteMetadata(String sessionId) {
        try {
            redisTemplate.opsForHash().delete(META_KEY, sessionId);
        } catch (Exception e) {
            log.debug("Redis deleteMetadata failed: {}", e.getMessage());
        }
    }

    /**
     * 列出所有会话元数据（按 updatedAt 降序）
     */
    public List<Map<String, Object>> listSessions() {
        try {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(META_KEY);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object val : raw.values()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = mapper.readValue((String) val, Map.class);
                result.add(meta);
            }
            result.sort((a, b) -> {
                long t1 = Long.parseLong(a.getOrDefault("updatedAt", "0").toString());
                long t2 = Long.parseLong(b.getOrDefault("updatedAt", "0").toString());
                return Long.compare(t2, t1); // 降序
            });
            return result;
        } catch (Exception e) {
            log.debug("Redis listSessions failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
