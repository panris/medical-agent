package com.medicalagent.agent;

import com.medicalagent.model.AgentState;
import com.medicalagent.repository.HybridSearchRepository;
import com.medicalagent.service.EmbeddingService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 医疗检索 Agent (T2.4)
 * 调用混合检索（向量 + 全文），注入 context 到状态
 */
@Component
public class RetrievalAgent {

    private static final double CONFIDENCE_THRESHOLD = 0.4;

    private final HybridSearchRepository searchRepository;
    private final EmbeddingService embeddingService;

    public RetrievalAgent(HybridSearchRepository searchRepository,
                          EmbeddingService embeddingService) {
        this.searchRepository = searchRepository;
        this.embeddingService = embeddingService;
    }

    /**
     * 执行检索
     * @return true 表示检索结果可信度足够，可继续诊断
     */
    public boolean retrieve(AgentState state) {
        String query = buildQuery(state);
        if (query == null || query.isBlank()) {
            state.setConfidenceScore(0.0);
            state.setCurrentAgent("retrieval");
            return false;
        }

        List<Map<String, Object>> docs;

        // 先尝试生成向量，走混合检索
        float[] embedding = embeddingService.embed(query);
        if (embedding != null) {
            docs = searchRepository.search(query, embedding);
        } else {
            // 向量生成失败，降级为纯文本检索
            docs = searchRepository.searchTextOnly(query);
        }

        double avgConfidence = docs.stream()
                .mapToDouble(doc -> ((Number) doc.getOrDefault("final_score", 0.0)).doubleValue())
                .average()
                .orElse(0.0);

        state.setRetrievedDocs(docs);
        state.setConfidenceScore(avgConfidence);
        state.setCurrentAgent("retrieval");

        return avgConfidence >= CONFIDENCE_THRESHOLD;
    }

    private String buildQuery(AgentState state) {
        Map<String, Object> history = state.getPatientHistory();
        if (history == null) return null;

        StringBuilder sb = new StringBuilder();
        if (history.containsKey("chief_complaint")) sb.append(history.get("chief_complaint"));
        if (history.containsKey("symptoms")) sb.append(" ").append(history.get("symptoms"));
        if (history.containsKey("medical_history")) sb.append(" ").append(history.get("medical_history"));

        return sb.toString().trim();
    }
}
