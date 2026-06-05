package com.medicalagent.agent;

import com.medicalagent.config.ModelFactory;
import com.medicalagent.model.AgentState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 诊断建议 Agent (T2.5)
 * 组装 Prompt，调用 LLM 输出结构化 JSON，附带免责声明
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

    private static final String DISCLAIMER =
            "【免责声明】以上分析仅供参考，不构成医疗诊断。如有不适请及时前往正规医疗机构就诊。";

    private final ModelFactory modelFactory;

    public DiagnosisAgent(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 执行诊断
     * @return 诊断结果 Map（含 disclaimer）
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

        result.put("disclaimer", true);
        result.put("disclaimer_text", DISCLAIMER);

        state.setDiagnosisResult(result);
        state.setCurrentAgent("diagnose");
        state.setNeedsMoreInfo(false);

        return result;
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
