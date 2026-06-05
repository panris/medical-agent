package com.medicalagent.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.DisabledChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * 模型工厂 (T3.2)
 * 动态实例化 LLM，支持多 provider + fallback
 */
@Component
public class ModelFactory {

    @Value("${llm.default.provider:openai}")
    private String defaultProvider;

    @Value("${llm.default.model:gpt-4o-mini}")
    private String defaultModel;

    @Value("${llm.default.base-url:https://api.openai.com}")
    private String defaultBaseUrl;

    @Value("${llm.default.api-key:}")
    private String defaultApiKey;

    @Value("${llm.default.timeout:30}")
    private int timeoutSeconds;

    @Value("${llm.default.max-tokens:2048}")
    private int maxTokens;

    @Value("${llm.default.temperature:0.2}")
    private double temperature;

    /** 缓存已创建的模型实例 */
    private final Map<String, ChatLanguageModel> modelCache = new HashMap<>();

    /**
     * 获取默认模型
     */
    public ChatLanguageModel getDefaultModel() {
        return getModel(defaultProvider, defaultModel);
    }

    /**
     * 获取指定 provider + model 的实例（带缓存）
     */
    public ChatLanguageModel getModel(String provider, String modelName) {
        String cacheKey = provider + ":" + modelName;
        return modelCache.computeIfAbsent(cacheKey, k -> createModel(provider, modelName));
    }

    /**
     * 获取带 fallback 的模型链
     * 依次尝试 providers 列表，第一个成功即返回
     */
    public ChatLanguageModel getModelWithFallback(String primaryProvider, String primaryModel,
                                                   String fallbackProvider, String fallbackModel) {
        // LangChain4j 没有原生 fallback 链，这里包装一下
        return new FallbackChatModel(
                getModel(primaryProvider, primaryModel),
                getModel(fallbackProvider, fallbackModel)
        );
    }

    /**
     * 从数据库配置创建模型（热更新用）
     */
    public ChatLanguageModel createFromConfig(Map<String, Object> config) {
        String provider = (String) config.getOrDefault("provider", "openai");
        String modelName = (String) config.getOrDefault("model_name", "gpt-4o-mini");
        return createModel(provider, modelName, config);
    }

    /**
     * 清除缓存（配置热更新时调用）
     */
    public void evictCache() {
        modelCache.clear();
    }

    public void evictCache(String provider, String model) {
        modelCache.remove(provider + ":" + model);
    }

    /**
     * 创建模型实例
     */
    private ChatLanguageModel createModel(String provider, String modelName) {
        Map<String, Object> config = new HashMap<>();
        config.put("base_url", defaultBaseUrl);
        config.put("api_key", defaultApiKey);
        return createModel(provider, modelName, config);
    }

    private ChatLanguageModel createModel(String provider, String modelName, Map<String, Object> config) {
        String baseUrl = (String) config.getOrDefault("base_url", defaultBaseUrl);
        String apiKey = (String) config.getOrDefault("api_key", defaultApiKey);

        if (apiKey == null || apiKey.isBlank()) {
            return new DisabledChatModel();
        }

        // 所有 OpenAI 兼容接口统一走 OpenAiChatModel
        // 包括: OpenAI, Evol 本地代理, DeepSeek 等
        return OpenAiChatModel.builder()
                .baseUrl(normalizeBaseUrl(baseUrl, provider))
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(true)
                .build();
    }

    /**
     * URL 规范化
     * Evol: http://127.0.0.1:12654/openclaw-proxy/v1 → 去掉尾部 /v1 (LangChain4j 会自动加)
     */
    private String normalizeBaseUrl(String baseUrl, String provider) {
        if (baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/")) {
            baseUrl = baseUrl.replaceAll("/v1/?$", "");
        }
        return baseUrl;
    }
}
