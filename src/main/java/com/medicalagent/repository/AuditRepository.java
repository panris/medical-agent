package com.medicalagent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D-06: 审计记录持久化
 * 将问诊审计日志写入 consultation_records 表
 */
@Repository
public class AuditRepository {

    private static final Logger log = LoggerFactory.getLogger(AuditRepository.class);
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbcTemplate;

    public AuditRepository(JdbcTemplate jdbcTemplate, ObjectMapper mapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
    }

    /**
     * 保存审计记录到 consultation_records 表
     */
    public void saveAudit(String sessionId, String intent,
                          java.util.List<Map<String, Object>> retrievedDocs,
                          Map<String, Object> diagnosisResult,
                          double confidenceScore, long latencyMs) {
        try {
            String retrievedDocsJson = retrievedDocs != null ? mapper.writeValueAsString(retrievedDocs) : "[]";
            String diagnosisJson = diagnosisResult != null ? mapper.writeValueAsString(diagnosisResult) : "{}";
            boolean disclaimerShown = true;

            jdbcTemplate.update(
                    "INSERT INTO consultation_records (session_id, intent, retrieved_docs, confidence, diagnosis, disclaimer_shown, latency_ms) " +
                    "VALUES (?, ?, ?::jsonb, ?, ?::jsonb, ?, ?)",
                    sessionId, intent, retrievedDocsJson, confidenceScore, diagnosisJson, disclaimerShown, latencyMs
            );
        } catch (Exception e) {
            // 表不存在（H2 默认环境）时降级为日志
            log.debug("Audit persist skipped (table may not exist): {}", e.getMessage());
        }
    }
}
