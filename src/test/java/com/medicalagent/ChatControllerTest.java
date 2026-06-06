package com.medicalagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalagent.api.ChatController;
import com.medicalagent.model.AgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Q-01: ChatController 集成测试
 * 测试 SSE 事件序列和 session 管理
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("健康检查返回 UP")
    void testHealth() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("不存在的 session history 返回错误")
    void testHistoryNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/chat/session/nonexistent/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("end session 返回 completed")
    void testEndSession() throws Exception {
        mockMvc.perform(post("/api/v1/chat/session/test-end/end"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));
    }

    @Test
    @DisplayName("SSE stream 接口正常响应")
    void testStreamResponse() throws Exception {
        String body = mapper.writeValueAsString(
                Map.of("sessionId", "test-stream", "message", "头疼"));
        mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("LlmConfig GET 返回配置结构")
    void testConfigGet() throws Exception {
        mockMvc.perform(get("/api/v1/config/llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").exists())
                .andExpect(jsonPath("$.apiKeyConfigured").exists());
    }
}
