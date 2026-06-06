package com.medicalagent.agent;

import com.medicalagent.config.ModelFactory;
import com.medicalagent.model.AgentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 路由 Agent (T2.2)
 * 通过 LLM 进行意图分类，返回下一个要执行的 Agent 节点
 * LLM 不可用时回退到正则匹配
 */
@Component
public class RouterAgent {

    private static final Logger log = LoggerFactory.getLogger(RouterAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是一个医疗问诊系统的意图分类器。根据用户的最新消息和对话上下文，判断用户的意图。

            你必须从以下四个意图中选择一个：
            - collect_info: 用户在描述症状、回答追问、或刚开始对话，需要继续收集患者信息
            - retrieval: 用户已提供了足够的信息（至少有主诉/症状），可以进行知识检索和诊断
            - emergency: 用户描述了紧急症状（如昏迷、休克、大出血、胸痛、呼吸困难、窒息等），需要立即给出紧急指引
            - end: 用户明确表示结束对话（如感谢、再见等）

            判断优先级：emergency > end > retrieval > collect_info

            你必须返回严格的 JSON 格式，不要包含任何其他文字：
            {"intent": "collect_info|retrieval|emergency|end", "reason": "判断理由"}
            """;

    // fallback 正则（LLM 不可用时使用）
    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^(你好|您好|hi|hello|嗨|hey|早上好|下午好|晚上好|早)[\\s!！.。]*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EMERGENCY_PATTERN = Pattern.compile(
            ".*(急诊|抢救|急救|昏迷|休克|大出血|胸痛|呼吸困难|窒息|中毒|溺水|触电|心脏病发作|heart attack|emergency|ambulance|叫救护车).*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FAREWELL_PATTERN = Pattern.compile(
            "^(谢谢|感谢|再见|拜拜|bye|好的谢谢|谢谢医生)[\\s!！.。]*$",
            Pattern.CASE_INSENSITIVE
    );

    private static final List<String> VALID_INTENTS = List.of("collect_info", "retrieval", "emergency", "end");

    private final ModelFactory modelFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RouterAgent(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 执行意图分类
     * @return 下一个 Agent 节点名称
     */
    public String route(AgentState state) {
        String lastMessage = getLastUserMessage(state);
        if (lastMessage == null || lastMessage.isBlank()) {
            return "collect_info";
        }

        // 尝试 LLM 意图识别
        String intent = routeByLLM(state, lastMessage);
        if (intent != null) {
            log.info("LLM 路由结果: intent={}, lastMessage={}", intent, lastMessage.substring(0, Math.min(lastMessage.length(), 50)));
            return intent;
        }

        // fallback: 正则匹配
        String fallbackIntent = routeByRegex(state, lastMessage);
        log.info("Regex fallback 路由结果: intent={}, lastMessage={}", fallbackIntent, lastMessage.substring(0, Math.min(lastMessage.length(), 50)));
        return fallbackIntent;
    }

    /**
     * 通过 LLM 进行意图识别
     */
    private String routeByLLM(AgentState state, String lastMessage) {
        ChatLanguageModel model = modelFactory.getDefaultModel();
        if (model == null) {
            log.warn("LLM 不可用，使用正则 fallback");
            return null;
        }

        try {
            String context = buildContext(state);
            String userContent = context + "\n\n用户最新消息：" + lastMessage;

            AiMessage response = model.generate(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(userContent)
            ).content();

            String text = response.text().trim();
            // 提取 JSON（兼容 markdown 代码块包裹）
            if (text.contains("```")) {
                int start = text.indexOf("{");
                int end = text.lastIndexOf("}") + 1;
                if (start >= 0 && end > start) {
                    text = text.substring(start, end);
                }
            }

            Map<String, Object> parsed = objectMapper.readValue(text, Map.class);
            String intent = (String) parsed.get("intent");

            if (intent != null && VALID_INTENTS.contains(intent)) {
                String reason = (String) parsed.getOrDefault("reason", "");
                log.debug("LLM 路由详情: intent={}, reason={}", intent, reason);
                return intent;
            }

            log.warn("LLM 返回了无效的 intent: {}, 原始响应: {}", intent, text);
            return null;
        } catch (Exception e) {
            log.warn("LLM 路由失败，使用正则 fallback: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建对话上下文（给 LLM 判断意图用）
     */
    private String buildContext(AgentState state) {
        StringBuilder sb = new StringBuilder("【对话上下文】\n");

        // 患者已有信息
        Map<String, Object> history = state.getPatientHistory();
        if (history != null && !history.isEmpty()) {
            sb.append("已收集的患者信息：");
            history.forEach((k, v) -> sb.append(k).append("=").append(v).append("; "));
            sb.append("\n");
        } else {
            sb.append("尚未收集患者信息\n");
        }

        // 最近的对话（最后 4 条）
        List<AgentState.Message> messages = state.getMessages();
        if (messages != null && !messages.isEmpty()) {
            int start = Math.max(0, messages.size() - 4);
            sb.append("近期对话：\n");
            for (int i = start; i < messages.size(); i++) {
                AgentState.Message msg = messages.get(i);
                sb.append(msg.getType()).append(": ").append(msg.getContent()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 正则 fallback（LLM 不可用时使用）
     */
    private String routeByRegex(AgentState state, String lastMessage) {
        // 紧急 → 直接给出紧急指引
        if (EMERGENCY_PATTERN.matcher(lastMessage).find()) {
            return "emergency";
        }

        // 告别 → 结束
        if (FAREWELL_PATTERN.matcher(lastMessage).matches()) {
            return "end";
        }

        // 检查是否已有足够的患者信息
        boolean hasBasicInfo = hasMinimumInfo(state);
        if (!hasBasicInfo) {
            return "collect_info";
        }

        return "retrieval";
    }

    /**
     * 路由后的处理函数，用于更新状态中的路由结果
     */
    public void postRoute(AgentState state, String nextNode) {
        state.setRouteIntent(nextNode);
        state.setCurrentAgent(nextNode);
    }

    private boolean hasMinimumInfo(AgentState state) {
        Map<String, Object> history = state.getPatientHistory();
        if (history == null || history.isEmpty()) {
            return false;
        }
        return history.containsKey("symptoms") || history.containsKey("chief_complaint");
    }

    private String getLastUserMessage(AgentState state) {
        List<AgentState.Message> messages = state.getMessages();
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentState.Message msg = messages.get(i);
            if ("user".equals(msg.getType())) {
                return msg.getContent();
            }
        }
        return null;
    }
}
