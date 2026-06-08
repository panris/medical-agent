package com.medicalagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Embedding 服务 (T1.3)
 * 调用外部 API 生成文本向量
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    @Value("${embedding.base-url:https://api.openai.com/v1}/embeddings")
    private String apiUrl;

    @Value("${embedding.api-key:}")
    private String apiKey; // WARNING: never log this field directly

    @Value("${embedding.model:text-embedding-3-small}")
    private String model;

    @Value("${embedding.dimension:1536}")
    private int dimension;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 生成文本向量
     * @return 向量数组，失败返回 null
     */
    @SuppressWarnings("unchecked")
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;

        try {
            var request = Map.of(
                    "model", model,
                    "input", text,
                    "dimensions", dimension
            );

            var headers = new org.springframework.http.HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

            var entity = new org.springframework.http.HttpEntity<>(request, headers);

            var response = restTemplate.postForObject(apiUrl, entity, Map.class);

            if (response != null && response.containsKey("data")) {
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                if (!data.isEmpty()) {
                    List<Number> embedding = (List<Number>) data.get(0).get("embedding");
                    float[] result = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        result[i] = embedding.get(i).floatValue();
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("Embedding API call failed", e);
        }

        return null;
    }
}
