package com.medicalagent.api;

import com.medicalagent.config.LlmConfigService;
import com.medicalagent.config.ModelFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 配置管理 API
 * GET  /api/v1/config/llm       - 读取当前配置
 * POST /api/v1/config/llm       - 保存配置 + 清空模型缓存
 * GET  /api/v1/config/llm/test  - 测试连通性（返回详细错误信息）
 */
@RestController
@RequestMapping("/api/v1/config")
public class LlmConfigController {

    private final LlmConfigService configService;
    private final ModelFactory modelFactory;

    public LlmConfigController(LlmConfigService configService, ModelFactory modelFactory) {
        this.configService = configService;
        this.modelFactory = modelFactory;
    }

    @GetMapping("/llm")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> config = configService.loadConfig();
        result.put("baseUrl",      config.get("baseUrl"));
        result.put("defaultModel", config.get("defaultModel"));
        result.put("temperature",   config.get("temperature"));
        result.put("maxTokens",    config.get("maxTokens"));
        result.put("apiKeyConfigured",
                configService.getDecryptedApiKey() != null && !configService.getDecryptedApiKey().isBlank());
        return result;
    }

    @PostMapping("/llm")
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> body) {
        Map<String, Object> config = configService.loadConfig();

        if (body.containsKey("baseUrl"))     config.put("baseUrl",     body.get("baseUrl"));
        if (body.containsKey("apiKey") && body.get("apiKey") != null
                && !body.get("apiKey").toString().isBlank()) {
            config.put("apiKey", body.get("apiKey"));
        }
        if (body.containsKey("defaultModel")) config.put("defaultModel", body.get("defaultModel"));
        if (body.containsKey("temperature")) config.put("temperature",   body.get("temperature"));
        if (body.containsKey("maxTokens"))  config.put("maxTokens",   body.get("maxTokens"));

        configService.saveConfig(config);
        modelFactory.evictCache();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "配置已保存，模型缓存已清空");
        return result;
    }

    @GetMapping("/llm/test")
    public Map<String, Object> testConnection() {
        Map<String, Object> config = configService.loadConfig();
        return configService.testConnection(config);  // 直接返回详细结果
    }
}
