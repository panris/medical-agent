package com.medicalagent.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LangGraph 状态等价类 (T2.1)
 * 使用普通 Java 类手动实现状态流转
 */
public class AgentState {

    /** 完整对话历史 */
    private List<Message> messages = new ArrayList<>();

    /** 患者历史信息 */
    private Map<String, Object> patientHistory = new HashMap<>();

    /** 检索到的医疗知识库文档 */
    private List<Map<String, Object>> retrievedDocs = new ArrayList<>();

    /** 当前执行的 Agent 名称 */
    private String currentAgent = "router";

    /** 是否需要继续追问 */
    private boolean needsMoreInfo = true;

    /** 最终诊断建议 */
    private Map<String, Object> diagnosisResult = null;

    /** 检索置信度 */
    private double confidenceScore = 0.0;

    /** 会话 ID */
    private String sessionId;

    /** 错误信息 */
    private String error;

    /** 当前追问轮次 */
    private int turnCount = 0;

    /** 嵌套消息类 */
    public static class Message {
        private String type;
        private String content;

        public Message() {}
        public Message(String type, String content) {
            this.type = type;
            this.content = content;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    // Getter / Setter
    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }

    public Map<String, Object> getPatientHistory() { return patientHistory; }
    public void setPatientHistory(Map<String, Object> patientHistory) { this.patientHistory = patientHistory; }

    public List<Map<String, Object>> getRetrievedDocs() { return retrievedDocs; }
    public void setRetrievedDocs(List<Map<String, Object>> retrievedDocs) { this.retrievedDocs = retrievedDocs; }

    public String getCurrentAgent() { return currentAgent; }
    public void setCurrentAgent(String currentAgent) { this.currentAgent = currentAgent; }

    public boolean isNeedsMoreInfo() { return needsMoreInfo; }
    public void setNeedsMoreInfo(boolean needsMoreInfo) { this.needsMoreInfo = needsMoreInfo; }

    public Map<String, Object> getDiagnosisResult() { return diagnosisResult; }
    public void setDiagnosisResult(Map<String, Object> diagnosisResult) { this.diagnosisResult = diagnosisResult; }

    public double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(double confidenceScore) { this.confidenceScore = confidenceScore; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }
}
