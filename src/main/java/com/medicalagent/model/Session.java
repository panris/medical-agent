package com.medicalagent.model;

import java.util.Map;

/**
 * 会话实体 (T1.4)
 */
public class Session {

    private String id;
    private Map<String, Object> patientSummary;
    private Map<String, Object> state;
    private String status; // active | completed | expired
    private Long expiresAt;

    public Session() {}

    public Session(String id) {
        this.id = id;
        this.status = "active";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Map<String, Object> getPatientSummary() { return patientSummary; }
    public void setPatientSummary(Map<String, Object> patientSummary) { this.patientSummary = patientSummary; }

    public Map<String, Object> getState() { return state; }
    public void setState(Map<String, Object> state) { this.state = state; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
}
