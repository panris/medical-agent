package com.medicalagent.agent;

import com.medicalagent.config.ModelFactory;
import com.medicalagent.model.AgentState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 诊断建议 Agent (T2.5)
 * 组装 Prompt，调用 LLM 输出结构化 JSON，附带免责声明
 * 支持同步和流式两种调用模式
 */
@Component
public class DiagnosisAgent {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是一个智能医疗问诊助手。根据患者的症状描述和检索到的医疗知识，给出初步分析和建议。

            你必须遵循以下规则：
            1. 这只是初步分析，不能替代专业医生的诊断
            2. 如果症状严重，必须明确建议就医
            3. 输出必须为结构化 JSON 格式
            4. 不要开具处方药

            输出格式：
            {
                "analysis": "病情分析（简要）",
                "possible_conditions": ["可能的病症1", "可能的病症2"],
                "severity": "low|medium|high|critical",
                "recommendation": "建议措施",
                "department": "推荐科室",
                "disclaimer": true
            }
            """;

    /** 流式用的 prompt：先输出可读文本，最后输出 JSON */
    private static final String STREAMING_SYSTEM_PROMPT = """
            你是一个智能医疗问诊助手。根据患者的症状描述和检索到的医疗知识，给出初步分析和建议。

            规则：
            1. 这只是初步分析，不能替代专业医生的诊断
            2. 如果症状严重，必须明确建议就医
            3. 不要开具处方药

            请按以下格式输出，先输出用户可读的分析文本，最后输出结构化 JSON：

            ## 病情分析
            （详细分析患者的症状，可能的原因等）

            ## 可能相关
            - 症状1
            - 症状2

            ## 就医建议
            （具体的建议措施）

            ## 推荐科室
            （推荐的医院科室）

            ---JSON---
            {"analysis":"...","possible_conditions":["..."],"severity":"low|medium|high|critical","recommendation":"...","department":"...","disclaimer":true}
            """;

    private static final String DISCLAIMER =
            "【免责声明】以上分析仅供参考，不构成医疗诊断。如有不适请及时前往正规医疗机构就诊。";

    private static final String JSON_MARKER = "---JSON---";

    private final ModelFactory modelFactory;

    public DiagnosisAgent(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 执行诊断（同步模式）
     */
    public Map<String, Object> diagnose(AgentState state) {
        String prompt = buildPrompt(state);
        String retrievedContext = buildRetrievedContext(state);

        Map<String, Object> result;
        try {
            result = callLLM(prompt, retrievedContext);
        } catch (Exception e) {
            log.error("LLM call failed, using fallback", e);
            result = mockDiagnose();
        }

        return finalizeResult(result, state);
    }

    /**
     * 流式诊断 — 逐 token 回调，返回 CompletableFuture 等待完成
     *
     * @param state    当前状态
     * @param onToken  每个 token 的回调（可读文本部分）
     * @return CompletableFuture 完成时返回结构化诊断结果
     */
    public CompletableFuture<Map<String, Object>> diagnoseStreaming(
            AgentState state,
            java.util.function.Consumer<String> onToken) {

        String prompt = buildPrompt(state);
        String retrievedContext = buildRetrievedContext(state);
        String userContent = prompt + "\n\n" + retrievedContext;

        StreamingChatLanguageModel streamingModel = modelFactory.getDefaultStreamingModel();
        ChatLanguageModel syncModel = modelFactory.getDefaultModel();

        // 如果流式模型不可用，降级到同步
        if (streamingModel == null) {
            if (syncModel == null) {
                return CompletableFuture.completedFuture(finalizeResult(mockDiagnose(), state));
            }
            // 同步模式：一次性拿到结果
            try {
                Map<String, Object> result = callLLM(prompt, retrievedContext);
                result.put("disclaimer", true);
                result.put("disclaimer_text", DISCLAIMER);
                state.setDiagnosisResult(result);
                state.setCurrentAgent("diagnose");
                state.setNeedsMoreInfo(false);
                // 把整个结果作为一次 token 发送
                StringBuilder displayText = new StringBuilder();
                displayText.append("📋 **初步分析**\n\n");
                displayText.append(result.getOrDefault("analysis", "")).append("\n\n");
                @SuppressWarnings("unchecked")
                List<String> conditions = (List<String>) result.getOrDefault("possible_conditions", List.of());
                if (!conditions.isEmpty()) {
                    displayText.append("🔍 **可能相关**：").append(String.join("、", conditions)).append("\n\n");
                }
                displayText.append("🏥 **推荐科室**：").append(result.getOrDefault("department", "请咨询导诊")).append("\n\n");
                displayText.append("💊 **建议**：").append(result.getOrDefault("recommendation", "")).append("\n\n");
                onToken.accept(displayText.toString());
                return CompletableFuture.completedFuture(finalizeResult(result, state));
            } catch (Exception e) {
                log.error("Sync LLM call failed", e);
                return CompletableFuture.completedFuture(finalizeResult(mockDiagnose(), state));
            }
        }

        // 流式模式
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        StringBuilder fullResponse = new StringBuilder();

        streamingModel.generate(
                List.of(SystemMessage.from(STREAMING_SYSTEM_PROMPT), UserMessage.from(userContent)),
                new StreamingResponseHandler<AiMessage>() {
                    private boolean jsonStarted = false;
                    private final StringBuilder jsonBuffer = new StringBuilder();

                    @Override
                    public void onNext(String partialToken) {
                        if (jsonStarted) {
                            jsonBuffer.append(partialToken);
                            return;
                        }
                        fullResponse.append(partialToken);
                        int jsonIdx = fullResponse.indexOf(JSON_MARKER);
                        if (jsonIdx >= 0) {
                            jsonStarted = true;
                            jsonBuffer.append(fullResponse.substring(jsonIdx + JSON_MARKER.length()));
                        } else {
                            onToken.accept(partialToken);
                        }
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        try {
                            Map<String, Object> result = parseStreamingResult(fullResponse.toString());
                            future.complete(finalizeResult(result, state));
                        } catch (Exception e) {
                            log.warn("Failed to parse streaming result, using fallback", e);
                            future.complete(finalizeResult(mockDiagnose(), state));
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("Streaming LLM error", error);
                        future.complete(finalizeResult(mockDiagnose(), state));
                    }
                }
        );

        return future;
    }

    private Map<String, Object> parseStreamingResult(String fullText) {
        int jsonIdx = fullText.indexOf(JSON_MARKER);
        String jsonPart;
        if (jsonIdx >= 0) {
            jsonPart = fullText.substring(jsonIdx + JSON_MARKER.length()).trim();
        } else {
            // 没有 JSON marker，尝试从最后一个 { 解析
            int lastBrace = fullText.lastIndexOf('{');
            if (lastBrace >= 0) {
                jsonPart = fullText.substring(lastBrace).trim();
            } else {
                throw new RuntimeException("No JSON found in streaming response");
            }
        }
        return parseDiagnosisResponse(jsonPart);
    }

    private String buildPrompt(AgentState state) {
        Map<String, Object> history = state.getPatientHistory();
        StringBuilder sb = new StringBuilder("【患者信息】\n");
        if (history != null) {
            history.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
        }
        sb.append("\n【对话历史】\n");
        List<AgentState.Message> messages = state.getMessages();
        if (messages != null) {
            for (AgentState.Message msg : messages) {
                sb.append(msg.getType()).append(": ").append(msg.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildRetrievedContext(AgentState state) {
        List<Map<String, Object>> docs = state.getRetrievedDocs();
        if (docs == null || docs.isEmpty()) {
            return "（未检索到相关知识）";
        }
        StringBuilder sb = new StringBuilder("【知识库参考】\n");
        for (int i = 0; i < docs.size(); i++) {
            sb.append(i + 1).append(". ").append(docs.get(i).getOrDefault("content", ""));
            sb.append("\n来源: ").append(docs.get(i).getOrDefault("source_file", "未知")).append("\n");
        }
        return sb.toString();
    }

    private Map<String, Object> callLLM(String prompt, String context) {
        ChatLanguageModel model = modelFactory.getDefaultModel();
        if (model == null) {
            throw new IllegalStateException("AI 模型未配置，请在 config.html 页面配置 API Key");
        }
        String userContent = prompt + "\n\n" + context;
        AiMessage response = model.generate(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userContent)
        ).content();
        return parseDiagnosisResponse(response.text());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDiagnosisResponse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as JSON");
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("analysis", json);
            fallback.put("possible_conditions", List.of());
            fallback.put("severity", "medium");
            fallback.put("recommendation", "建议前往医院进行详细检查");
            fallback.put("department", "内科");
            return fallback;
        }
    }

    /** 将诊断结果写入 state 并返回 */
    private Map<String, Object> finalizeResult(Map<String, Object> result, AgentState state) {
        result.put("disclaimer", true);
        result.put("disclaimer_text", DISCLAIMER);
        state.setDiagnosisResult(result);
        state.setCurrentAgent("diagnose");
        state.setNeedsMoreInfo(false);
        return result;
    }

    private Map<String, Object> mockDiagnose() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysis", "当前 AI 模型不可用，建议前往医院进行详细检查。");
        result.put("possible_conditions", List.of());
        result.put("severity", "low");
        result.put("recommendation", "建议前往医院进行详细检查");
        result.put("department", "内科");
        return result;
    }
}
