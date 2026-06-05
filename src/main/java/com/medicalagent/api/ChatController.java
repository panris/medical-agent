package com.medicalagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalagent.agent.GraphOrchestrator;
import com.medicalagent.agent.GraphOrchestrator.StepResult;
import com.medicalagent.filter.SensitiveDataFilter;
import com.medicalagent.model.AgentState;
import com.medicalagent.repository.SessionRepository;
import com.medicalagent.service.ComplianceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 聊天 API (T4.2 + T5.1 + T5.2)
 * SSE (SseEmitter) + Redis Session + 输入脱敏 + 合规审计
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    @Autowired
    private GraphOrchestrator graphOrchestrator;

    @Autowired
    private SensitiveDataFilter sensitiveDataFilter;

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private SessionRepository sessionRepository;

    @Value("${session.ttl-minutes:30}")
    private long sessionTtlMinutes;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 分钟超时

        sseExecutor.execute(() -> {
            long startTime = System.currentTimeMillis();
            try {
                AgentState state = getOrCreateState(request.getSessionId());

                // T5.1: 输入脱敏
                String sanitized = sensitiveDataFilter.filter(request.getMessage());
                state.getMessages().add(new AgentState.Message("user", sanitized));

                List<StepResult> results = graphOrchestrator.executeUntilBlock(state);

                for (StepResult result : results) {
                    if (result.getMessage() == null) continue;

                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("node", result.getNode());
                    event.put("content", result.getMessage());
                    event.put("finished", result.isFinished());

                    if ("diagnose".equals(result.getNode()) && state.getDiagnosisResult() != null) {
                        event.put("diagnosis", state.getDiagnosisResult());
                    }

                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(mapper.writeValueAsString(event)));
                }

                // T5.2: 合规审计
                long latencyMs = System.currentTimeMillis() - startTime;
                log.info("Audit: {}", complianceService.buildAuditLog(
                        request.getSessionId(), state.getCurrentAgent(),
                        state.getRetrievedDocs(), state.getDiagnosisResult(),
                        state.getConfidenceScore(), latencyMs));

                // T5.2: 最后一帧 disclaimer
                emitter.send(SseEmitter.event()
                        .name("disclaimer")
                        .data(mapper.writeValueAsString(complianceService.buildDisclaimerEvent())));

                // 持久化到 Redis
                sessionRepository.save(request.getSessionId(), state, sessionTtlMinutes);

                emitter.complete();

            } catch (Exception e) {
                log.error("SSE stream error", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(mapper.writeValueAsString(Map.of("type", "error", "message", "处理请求时发生错误"))));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @GetMapping("/session/{sessionId}/history")
    public Map<String, Object> getHistory(@PathVariable String sessionId) {
        String json = sessionRepository.load(sessionId);
        if (json == null) return Map.of("error", "会话不存在或已过期");
        try {
            AgentState state = mapper.readValue(json, AgentState.class);
            return Map.of(
                    "sessionId", sessionId,
                    "messages", state.getMessages(),
                    "patientHistory", state.getPatientHistory() != null ? state.getPatientHistory() : Map.of(),
                    "status", state.isNeedsMoreInfo() ? "active" : "completed"
            );
        } catch (Exception e) {
            return Map.of("error", "会话数据解析失败");
        }
    }

    @PostMapping("/session/{sessionId}/end")
    public Map<String, Object> endSession(@PathVariable String sessionId) {
        sessionRepository.delete(sessionId);
        return Map.of("status", "completed", "sessionId", sessionId);
    }

    private AgentState getOrCreateState(String sessionId) {
        String json = sessionRepository.load(sessionId);
        if (json != null) {
            try {
                return mapper.readValue(json, AgentState.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize session, creating new one");
            }
        }
        AgentState state = new AgentState();
        state.setSessionId(sessionId);
        return state;
    }

    public static class ChatRequest {
        private String sessionId;
        private String message;
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
