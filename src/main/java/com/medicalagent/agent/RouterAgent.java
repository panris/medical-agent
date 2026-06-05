package com.medicalagent.agent;

import com.medicalagent.model.AgentState;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 路由 Agent (T2.2)
 * 轻量意图分类，返回下一个要执行的 Agent 节点
 */
@Component
public class RouterAgent {

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

    /**
     * 执行意图分类
     * @return 下一个 Agent 节点名称
     */
    public String route(AgentState state) {
        String lastMessage = getLastUserMessage(state);
        if (lastMessage == null || lastMessage.isBlank()) {
            return "collect_info";
        }

        // 紧急 → 直接给出紧急指引
        if (EMERGENCY_PATTERN.matcher(lastMessage).find()) {
            state.setCurrentAgent("emergency");
            return "emergency";
        }

        // 打招呼 → 信息采集
        if (GREETING_PATTERN.matcher(lastMessage).matches()) {
            return "collect_info";
        }

        // 告别 → 结束
        if (FAREWELL_PATTERN.matcher(lastMessage).matches()) {
            state.setCurrentAgent("end");
            state.setNeedsMoreInfo(false);
            return "end";
        }

        // 检查是否已有足够的患者信息
        boolean hasBasicInfo = hasMinimumInfo(state);
        if (!hasBasicInfo) {
            return "collect_info";
        }

        // 有信息 → 检索 + 诊断
        return "retrieval";
    }

    /**
     * 路由后的处理函数，用于更新状态中的路由结果
     */
    public void postRoute(AgentState state, String nextNode) {
        Map<String, Object> diagnosisResult = state.getDiagnosisResult();
        if (diagnosisResult == null) {
            state.setDiagnosisResult(Map.of("intent", nextNode));
        } else {
            diagnosisResult.put("intent", nextNode);
        }
        state.setCurrentAgent(nextNode);
    }

    /**
     * 检查是否已有最低限度的患者信息
     */
    private boolean hasMinimumInfo(AgentState state) {
        Map<String, Object> history = state.getPatientHistory();
        if (history == null || history.isEmpty()) {
            return false;
        }
        // 至少有症状描述
        return history.containsKey("symptoms") || history.containsKey("chief_complaint");
    }

    private String getLastUserMessage(AgentState state) {
        List<AgentState.Message> messages = state.getMessages();
        if (messages == null || messages.isEmpty()) return null;
        // 倒序找最后一条用户消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentState.Message msg = messages.get(i);
            if ("user".equals(msg.getType())) {
                return msg.getContent();
            }
        }
        return null;
    }
}
