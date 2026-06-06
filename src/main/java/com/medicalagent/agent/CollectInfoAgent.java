package com.medicalagent.agent;

import com.medicalagent.model.AgentState;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 信息采集 Agent (T2.3)
 * 多轮追问收集患者信息，最多 3 轮
 */
@Component
public class CollectInfoAgent {

    private static final int MAX_TURNS = 5;

    /** 采集阶段定义（按优先级） */
    private static final List<QuestionPhase> PHASES = Arrays.asList(
            new QuestionPhase("chief_complaint", "请问您主要有什么不舒服？（请描述主要症状）"),
            new QuestionPhase("duration", "这个症状持续多久了？"),
            new QuestionPhase("age_gender", "方便告知您的年龄和性别吗？（有助于初步判断）"),
            new QuestionPhase("medical_history", "您以前有过类似症状或相关疾病吗？"),
            new QuestionPhase("medication", "目前有在吃什么药吗？"),
            new QuestionPhase("allergy", "有没有药物过敏史？"),
            new QuestionPhase("severity", "疼痛或不适的程度如何？（1-10 分）")
    );

    /** 年龄提取 — 必须有"岁"后缀或上下文提示 */
    private static final Pattern AGE_PATTERN = Pattern.compile("(\\d{1,3})\\s*岁");
    private static final Pattern AGE_CONTEXT_PATTERN = Pattern.compile("年龄[^\\d]*(\\d{1,3})");

    /** 性别提取 */
    private static final Pattern MALE_PATTERN = Pattern.compile("男|男性|male");
    private static final Pattern FEMALE_PATTERN = Pattern.compile("女|女性|female");

    /**
     * 执行信息采集
     * @return 返回追问文本，null 表示信息已足够
     */
    public String collect(AgentState state) {
        Map<String, Object> history = state.getPatientHistory();
        if (history == null) {
            history = new HashMap<>();
            state.setPatientHistory(history);
        }

        // 从最新用户消息中提取信息
        String lastMessage = getLastUserMessage(state);
        if (lastMessage != null) {
            extractInfo(history, lastMessage);
        }

        // 检查轮次限制
        if (state.getTurnCount() >= MAX_TURNS) {
            state.setNeedsMoreInfo(false);
            return null;
        }

        // 找到下一个缺失的信息项
        for (QuestionPhase phase : PHASES) {
            if (!history.containsKey(phase.key)) {
                state.setTurnCount(state.getTurnCount() + 1);
                state.setCurrentAgent("collect_info");
                return phase.question;
            }
        }

        // 所有必要信息已收集完毕
        state.setNeedsMoreInfo(false);
        return null;
    }

    /**
     * 从用户消息中提取结构化信息
     */
    private void extractInfo(Map<String, Object> history, String message) {
        // 提取年龄
        if (!history.containsKey("age")) {
            Matcher ageMatcher = AGE_PATTERN.matcher(message);
            if (ageMatcher.find()) {
                history.put("age", ageMatcher.group(1));
            } else {
                Matcher ageCtxMatcher = AGE_CONTEXT_PATTERN.matcher(message);
                if (ageCtxMatcher.find()) {
                    history.put("age", ageCtxMatcher.group(1));
                }
            }
        }

        // 提取性别
        if (!history.containsKey("gender")) {
            if (MALE_PATTERN.matcher(message).find()) {
                history.put("gender", "男");
            } else if (FEMALE_PATTERN.matcher(message).find()) {
                history.put("gender", "女");
            }
        }

        // 如果没有主诉，把当前消息当作主诉
        if (!history.containsKey("chief_complaint") && message != null && message.length() > 5) {
            // 排除纯回答（太短的或纯数字）
            if (message.length() > 10 || !message.matches("^[\\d.\\s]+$")) {
                history.put("chief_complaint", message);
                history.put("symptoms", message);
            }
        }

        // 提取持续时间
        if (!history.containsKey("duration")) {
            if (message.contains("天") || message.contains("周") || message.contains("月") ||
                message.contains("年") || message.contains("小时") || message.contains("分钟")) {
                history.put("duration", message);
            }
        }
    }

    private String getLastUserMessage(AgentState state) {
        List<AgentState.Message> messages = state.getMessages();
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentState.Message msg = messages.get(i);
            if ("user".equals(msg.getType())) return msg.getContent();
        }
        return null;
    }

    private static class QuestionPhase {
        final String key;
        final String question;

        QuestionPhase(String key, String question) {
            this.key = key;
            this.question = question;
        }
    }
}
