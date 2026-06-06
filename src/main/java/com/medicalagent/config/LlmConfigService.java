package com.medicalagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 配置文件读写服务
 * Q-09: API Key 加密存储（Spring Security Encryptors）
 * 配置文件路径由 llm.config-file 指定，默认 config/llm-config.json
 */
@Service
public class LlmConfigService {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigService.class);
    private static final String ENCRYPTED_PREFIX = "ENC(";
    private static final String ENCRYPTED_SUFFIX = ")";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final String configFilePath;

    /** 加密密钥：来自环境变量 ENC_PASSWORD，默认用机器名（开发环境） */
    private final TextEncryptor textEncryptor;

    public LlmConfigService(@Value("${llm.config-file:config/llm-config.json}") String configFilePath) {
        this.configFilePath = configFilePath;
        String password = System.getenv("ENC_PASSWORD");
        if (password == null || password.isBlank()) {
            // 开发环境 fallback：用用户目录路径做盐
            String salt = System.getProperty("user.home", "medical-agent");
            password = "med-agent-" + salt.hashCode();
            log.info("ENC_PASSWORD not set, using derived key (set env ENC_PASSWORD for production)");
        }
        this.textEncryptor = Encryptors.text(password, "deadbeef");
    }

    /**
     * 读取 LLM 配置（merge 默认值 + 文件值）
     * 自动解密加密的 apiKey
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadConfig() {
        Map<String, Object> config = defaultConfig();

        File file = new File(configFilePath);
        if (!file.exists()) {
            try {
                Path p = Paths.get(configFilePath);
                if (Files.exists(p)) {
                    Map<String, Object> fileData = objectMapper.readValue(p.toFile(), Map.class);
                    config.putAll(fileData);
                }
            } catch (Exception ignored) {}
            return config;
        }

        try {
            Map<String, Object> fileData = objectMapper.readValue(file, Map.class);
            config.putAll(fileData);
        } catch (Exception e) {
            log.error("读取配置文件失败: {}", e.getMessage());
        }
        return config;
    }

    /**
     * 获取解密后的 API Key
     */
    public String getDecryptedApiKey() {
        Map<String, Object> config = loadConfig();
        String apiKey = (String) config.getOrDefault("apiKey", "");
        return decryptIfNeeded(apiKey);
    }

    /**
     * 保存 LLM 配置到文件
     * Q-09: apiKey 加密后存储
     */
    public void saveConfig(Map<String, Object> config) {
        try {
            // 加密 apiKey
            Map<String, Object> toSave = new LinkedHashMap<>(config);
            Object apiKeyObj = toSave.get("apiKey");
            if (apiKeyObj != null && !apiKeyObj.toString().isBlank()) {
                String apiKey = apiKeyObj.toString();
                // 只加密未加密的 key
                if (!isEncrypted(apiKey)) {
                    toSave.put("apiKey", ENCRYPTED_PREFIX + textEncryptor.encrypt(apiKey) + ENCRYPTED_SUFFIX);
                }
            }

            File file = new File(configFilePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            objectMapper.writeValue(file, toSave);
        } catch (Exception e) {
            throw new RuntimeException("保存 LLM 配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 测试连通性
     */
    public Map<String, Object> testConnection(Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        String baseUrl = (String) config.getOrDefault("baseUrl", "https://api.openai.com/v1");
        String apiKey = decryptIfNeeded((String) config.getOrDefault("apiKey", ""));
        String model = (String) config.getOrDefault("defaultModel", "gpt-4o-mini");

        if (apiKey == null || apiKey.isBlank()) {
            result.put("success", false);
            result.put("message", "API Key 为空");
            return result;
        }

        if (!apiKey.matches("\\A\\p{ASCII}*\\z")) {
            result.put("success", false);
            result.put("message", "API Key 格式不正确（不能包含中文或特殊字符）");
            return result;
        }

        String url = baseUrl;
        if (!url.endsWith("/")) url += "/";
        url += "chat/completions";

        String body = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":5}",
                model.replace("\"", "\\\"")
        );

        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(15))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();

            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            int code = response.statusCode();
            String respBody = response.body() == null ? "" : response.body();

            if (code == 200) {
                result.put("success", true);
                result.put("message", "连接成功 (HTTP 200)");
            } else if (code == 400 || code == 422) {
                result.put("success", true);
                result.put("message", "连接成功 (HTTP " + code + ", 鉴权通过)");
            } else {
                result.put("success", false);
                result.put("message", "HTTP " + code + ": " + truncate(respBody, 200));
            }
            result.put("statusCode", code);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", "API Key 格式不正确（含非法字符，请重新填写）");
        } catch (java.net.http.HttpTimeoutException e) {
            result.put("success", false);
            result.put("message", "请求超时（15s），请检查 Base URL 是否可访问");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + truncate(e.getMessage(), 200));
        }
        return result;
    }

    // --- 加密/解密工具 ---

    private boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENCRYPTED_PREFIX) && value.endsWith(ENCRYPTED_SUFFIX);
    }

    private String decryptIfNeeded(String value) {
        if (!isEncrypted(value)) return value;
        try {
            String encrypted = value.substring(ENCRYPTED_PREFIX.length(),
                    value.length() - ENCRYPTED_SUFFIX.length());
            return textEncryptor.decrypt(encrypted);
        } catch (Exception e) {
            log.error("Failed to decrypt API Key (wrong ENC_PASSWORD?): {}", e.getMessage());
            return "";
        }
    }

    private Map<String, Object> defaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("baseUrl", "https://api.openai.com/v1");
        config.put("apiKey", "");
        config.put("defaultModel", "gpt-4o-mini");
        config.put("temperature", 0.2);
        config.put("maxTokens", 2048);
        return config;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
