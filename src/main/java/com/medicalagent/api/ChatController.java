package com.medicalagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalagent.agent.DiagnosisAgent;
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
 * 聊天 API
 * SSE (SseEmitter) + 流式 token 推送 + Redis Session + 输入脱敏 + 合规审计
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sse-executor");
        t.setDaemon(true);
        return t;
    });

    private final ObjectMapper mapper;

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        sseExecutor.shutdown();
    }

    private final GraphOrchestrator graphOrchestrator;
    private final DiagnosisAgent diagnosisAgent;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final ComplianceService complianceService;
    private final SessionRepository sessionRepository;

    public ChatController(GraphOrchestrator graphOrchestrator,
                          DiagnosisAgent diagnosisAgent,
                          SensitiveDataFilter sensitiveDataFilter,
                          ComplianceService complianceService,
                          SessionRepository sessionRepository,
                          ObjectMapper mapper) {
        this.graphOrchestrator = graphOrchestrator;
        this.diagnosisAgent = diagnosisAgent;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.complianceService = complianceService;
        this.sessionRepository = sessionRepository;
        this.mapper = mapper;
    }

    @Value("${session.ttl-minutes:30}")
    private long sessionTtlMinutes;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);

        sseExecutor.execute(() -> {
            long startTime = System.currentTimeMillis();
            try {
                AgentState state = getOrCreateState(request.getSessionId());
                boolean isNewSession = state.getMessages().isEmpty();

                // 输入脱敏
                String sanitized = sensitiveDataFilter.filter(request.getMessage());
                state.getMessages().add(new AgentState.Message("user", sanitized));

                // 新会话：保存侧边栏元数据
                if (isNewSession) {
                    sessionRepository.saveMetadata(request.getSessionId(), request.getMessage());
                }

                // 执行状态图（到 diagnose 节点前）
                List<StepResult> results = graphOrchestrator.executeUntilBlock(state);

                for (StepResult result : results) {
                    // diagnose 节点标记为 pendingExecution（message=null），需要 Controller 执行流式 LLM
                    if (result.isPendingExecution()) {
                        streamDiagnosis(emitter, state);
                        continue;
                    }

                    // 其他节点的 null message 直接跳过
                    if (result.getMessage() == null) continue;

                    // D-01: 将 assistant 消息写入 state（用于历史恢复）
                    state.getMessages().add(new AgentState.Message("assistant", result.getMessage()));

                    // 非 diagnose 节点，正常推送
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("node", result.getNode());
                    event.put("content", result.getMessage());
                    event.put("finished", false);
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(mapper.writeValueAsString(event)));
                }

                // 合规审计
                long latencyMs = System.currentTimeMillis() - startTime;
                log.info("Audit: {}", complianceService.buildAuditLog(
                        request.getSessionId(), state.getCurrentAgent(),
                        state.getRetrievedDocs(), state.getDiagnosisResult(),
                        state.getConfidenceScore(), latencyMs));

                // 最后一帧 disclaimer
                emitter.send(SseEmitter.event()
                        .name("disclaimer")
                        .data(mapper.writeValueAsString(complianceService.buildDisclaimerEvent())));

                // 持久化
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

    /**
     * 流式推送诊断结果 — 逐 token 发送
     */
    private void streamDiagnosis(SseEmitter emitter, AgentState state) {
        try {
            diagnosisAgent.diagnoseStreaming(state, token -> {
                try {
                    Map<String, Object> tokenEvent = new LinkedHashMap<>();
                    tokenEvent.put("type", "token");
                    tokenEvent.put("content", token);
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(mapper.writeValueAsString(tokenEvent)));
                } catch (Exception e) {
                    log.warn("Failed to send streaming token", e);
                }
            }).thenAccept(result -> {
                try {
                    // 发送结构化诊断数据（用于卡片渲染）
                    Map<String, Object> diagEvent = new LinkedHashMap<>();
                    diagEvent.put("type", "diagnosis");
                    diagEvent.put("diagnosis", result);
                    diagEvent.put("finished", true);
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(mapper.writeValueAsString(diagEvent)));

                    // D-01: 将诊断结果文本写入 state
                    if (result != null && result.containsKey("summary")) {
                        state.getMessages().add(new AgentState.Message("assistant", result.get("summary").toString()));
                    }
                } catch (Exception e) {
                    log.warn("Failed to send diagnosis event", e);
                }
            }).join(); // 等待流式完成
        } catch (Exception e) {
            log.error("Streaming diagnosis failed", e);
        }
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
                    "diagnosisResult", state.getDiagnosisResult() != null ? state.getDiagnosisResult() : Map.of(),
                    "status", state.isNeedsMoreInfo() ? "active" : "completed"
            );
        } catch (Exception e) {
            return Map.of("error", "会话数据解析失败");
        }
    }

    @PostMapping("/session/{sessionId}/end")
    public Map<String, Object> endSession(@PathVariable String sessionId) {
        sessionRepository.delete(sessionId);
        sessionRepository.deleteMetadata(sessionId);
        return Map.of("status", "completed", "sessionId", sessionId);
    }

    /**
     * 会话列表（侧边栏）
     */
    @GetMapping("/sessions")
    public List<Map<String, Object>> listSessions() {
        return sessionRepository.listSessions();
    }

    /**
     * 消息反馈（👍/👎）
     */
    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String messageId = body.get("messageId");
        String rating = body.get("rating"); // "positive" | "negative"
        String comment = body.getOrDefault("comment", "");
        log.info("Feedback: session={}, message={}, rating={}, comment={}", sessionId, messageId, rating, comment);
        complianceService.logFeedback(sessionId, messageId, rating, comment);
        return Map.of("status", "ok");
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
