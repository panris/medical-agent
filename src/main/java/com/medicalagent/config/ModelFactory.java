package com.medicalagent.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * 模型工厂 - 从 llm-config.json 动态加载配置，支持热更新
 */
@Component
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    private final LlmConfigService configService;
    private final Map<String, ChatLanguageModel> modelCache = new HashMap<>();
    private final Map<String, StreamingChatLanguageModel> streamingModelCache = new HashMap<>();

    public ModelFactory(LlmConfigService configService) {
        this.configService = configService;
    }

    public ChatLanguageModel getDefaultModel() {
        Map<String, Object> config = configService.loadConfig();
        String modelName = (String) config.getOrDefault("defaultModel", "gpt-4o-mini");
        double temperature = Double.parseDouble(config.getOrDefault("temperature", "0.2").toString());
        int maxTokens = Integer.parseInt(config.getOrDefault("maxTokens", "2048").toString());
        String cacheKey = modelName + "-t" + temperature + "-mt" + maxTokens;
        return modelCache.computeIfAbsent(cacheKey, k -> createModel(modelName, config, temperature, maxTokens));
    }

    public ChatLanguageModel getModel(String modelName) {
        Map<String, Object> config = configService.loadConfig();
        double temperature = Double.parseDouble(config.getOrDefault("temperature", "0.2").toString());
        int maxTokens = Integer.parseInt(config.getOrDefault("maxTokens", "2048").toString());
        String cacheKey = modelName + "-t" + temperature + "-mt" + maxTokens;
        return modelCache.computeIfAbsent(cacheKey, k -> createModel(modelName, config, temperature, maxTokens));
    }

    public StreamingChatLanguageModel getDefaultStreamingModel() {
        Map<String, Object> config = configService.loadConfig();
        String modelName = (String) config.getOrDefault("defaultModel", "gpt-4o-mini");
        double temperature = Double.parseDouble(config.getOrDefault("temperature", "0.2").toString());
        int maxTokens = Integer.parseInt(config.getOrDefault("maxTokens", "2048").toString());
        String cacheKey = modelName + "-t" + temperature + "-mt" + maxTokens;
        return streamingModelCache.computeIfAbsent(cacheKey, k -> createStreamingModel(modelName, config, temperature, maxTokens));
    }

    public ChatLanguageModel getModelWithFallback(String primary, String fallback) {
        return new FallbackChatModel(getModel(primary), getModel(fallback));
    }

    /**
     * 清空缓存（配置保存后调用，下次自动用新配置重建模型）
     */
    public void evictCache() {
        modelCache.clear();
        streamingModelCache.clear();
    }
    public void evictCache(String model) { modelCache.remove(model); streamingModelCache.remove(model); }

    private ChatLanguageModel createModel(String modelName, Map<String, Object> config, double temperature, int maxTokens) {
        String baseUrl = (String) config.getOrDefault("baseUrl", "https://api.openai.com/v1");
        String apiKey = configService.getDecryptedApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("API Key 未配置，无法创建模型");
            return null;
        }
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(30))
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    private StreamingChatLanguageModel createStreamingModel(String modelName, Map<String, Object> config, double temperature, int maxTokens) {
        String baseUrl = (String) config.getOrDefault("baseUrl", "https://api.openai.com/v1");
        String apiKey = configService.getDecryptedApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("API Key 未配置，无法创建流式模型");
            return null;
        }
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(30))
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
