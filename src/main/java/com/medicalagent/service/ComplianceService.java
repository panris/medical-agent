package com.medicalagent.service;

import com.medicalagent.repository.AuditRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合规审计服务 (T5.2) + D-06: 审计落库
 * 记录问诊链路，SSE 最后一帧追加 disclaimer
 */
@Component
public class ComplianceService {

    private final AuditRepository auditRepository;

    public ComplianceService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * 构建合规审计日志并持久化
     */
    public Map<String, Object> buildAuditLog(String sessionId, String intent,
                                               java.util.List<Map<String, Object>> retrievedDocs,
                                               Map<String, Object> diagnosisResult,
                                               double confidenceScore, long latencyMs) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("session_id", sessionId);
        log.put("timestamp", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
        log.put("intent", intent);
        log.put("retrieved_doc_count", retrievedDocs != null ? retrievedDocs.size() : 0);
        log.put("confidence_score", confidenceScore);
        log.put("severity", diagnosisResult != null ? diagnosisResult.get("severity") : "unknown");
        log.put("department", diagnosisResult != null ? diagnosisResult.get("department") : "unknown");
        log.put("latency_ms", latencyMs);
        log.put("disclaimer_shown", true);

        // D-06: 持久化到 consultation_records
        auditRepository.saveAudit(sessionId, intent, retrievedDocs, diagnosisResult, confidenceScore, latencyMs);

        return log;
    }

    /**
     * 构建 disclaimer SSE 事件数据
     */
    public Map<String, String> buildDisclaimerEvent() {
        Map<String, String> event = new LinkedHashMap<>();
        event.put("type", "disclaimer");
        event.put("text", "【免责声明】以上分析仅供参考，不构成医疗诊断。如有不适请及时前往正规医疗机构就诊。");
        return event;
    }
}
