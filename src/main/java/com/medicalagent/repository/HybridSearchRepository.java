package com.medicalagent.repository;

import com.medicalagent.model.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * 混合检索 Repository (T2.4)
 * 调用 PostgreSQL hybrid_search() PL/pgSQL 函数
 */
@Repository
public class HybridSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchRepository.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${retrieval.top-k:3}")
    private int topK;

    @Value("${retrieval.vector-weight:0.7}")
    private double vectorWeight;

    @Value("${retrieval.text-weight:0.3}")
    private double textWeight;

    @Value("${retrieval.confidence-threshold:0.4}")
    private double confidenceThreshold;

    public HybridSearchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 执行混合检索
     * @param queryText 用户查询文本
     * @param queryEmbedding 查询向量 (由上层调用 embedding 服务生成)
     * @return 检索结果列表
     */
    public List<Map<String, Object>> search(String queryText, float[] queryEmbedding) {
        // 对齐 init_db.sql hybrid_search() 函数签名：5 参数，无 WHERE/LIMIT（函数内部已处理）
        String sql = "SELECT id, content, source_file, chunk_index, " +
                "vector_score, text_score, final_score " +
                "FROM hybrid_search(?, ?, ?, ?, ?)";

        String vectorParam = arrayToPgVector(queryEmbedding);

        try {
            List<Map<String, Object>> results = jdbcTemplate.query(sql,
                    ps -> {
                        ps.setString(1, queryText);
                        ps.setString(2, vectorParam);
                        ps.setInt(3, topK);
                        ps.setDouble(4, vectorWeight);
                        ps.setDouble(5, textWeight);
                    },
                    (rs, rowNum) -> {
                        Map<String, Object> doc = new LinkedHashMap<>();
                        doc.put("doc_id", rs.getString("id"));
                        doc.put("content", rs.getString("content"));
                        doc.put("source_file", rs.getString("source_file"));
                        doc.put("chunk_index", rs.getInt("chunk_index"));
                        doc.put("vector_score", rs.getDouble("vector_score"));
                        doc.put("text_score", rs.getDouble("text_score"));
                        doc.put("final_score", rs.getDouble("final_score"));
                        return doc;
                    });

            // 客户端过滤置信度阈值
            results.removeIf(doc -> ((Double) doc.get("final_score")) < confidenceThreshold);
            return results;
        } catch (Exception e) {
            log.error("Hybrid search failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * 纯文本检索（无向量时 fallback）
     */
    public List<Map<String, Object>> searchTextOnly(String queryText) {
        // 对齐 init_db.sql knowledge_base 表：id 列，无 category 列（在 metadata JSONB 中）
        String sql = "SELECT id, content, source_file, " +
                "ts_rank(to_tsvector('simple', content), plainto_tsquery('simple', ?)) as final_score " +
                "FROM knowledge_base " +
                "WHERE to_tsvector('simple', content) @@ plainto_tsquery('simple', ?) " +
                "ORDER BY final_score DESC LIMIT ?";

        try {
            return jdbcTemplate.query(sql,
                    ps -> {
                        ps.setString(1, queryText);
                        ps.setString(2, queryText);
                        ps.setInt(3, topK);
                    },
                    (rs, rowNum) -> {
                        Map<String, Object> doc = new LinkedHashMap<>();
                        doc.put("doc_id", rs.getString("id"));
                        doc.put("content", rs.getString("content"));
                        doc.put("source_file", rs.getString("source_file"));
                        doc.put("vector_score", 0.0);
                        doc.put("text_score", rs.getDouble("final_score"));
                        doc.put("final_score", rs.getDouble("final_score"));
                        return doc;
                    });
        } catch (Exception e) {
            log.error("Text search failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * float[] → pgvector 格式 "[0.1,0.2,0.3]"
     */
    private String arrayToPgVector(float[] arr) {
        if (arr == null || arr.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
