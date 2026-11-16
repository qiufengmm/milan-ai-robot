package com.milanai.robot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    private Elasticsearch elasticsearch = new Elasticsearch();
    private Retrieval retrieval = new Retrieval();
    private Rerank rerank = new Rerank();

    @Data
    public static class Elasticsearch {
        private boolean enabled = true;
        private String url = "http://localhost:19200";
        private String indexName = "milan_rag_chunks_v1";
        private String analyzer = "ik_max_word";
        private String searchAnalyzer = "ik_smart";
        private int timeoutSeconds = 10;
    }

    @Data
    public static class Retrieval {
        private int vectorTopK = 10;
        private int keywordTopK = 10;
        private int fusedTopK = 10;
        private int rrfK = 60;
        private boolean logEvidence = false;
    }

    @Data
    public static class Rerank {
        private boolean enabled = true;
        private String model = "qwen3-rerank";
        private String apiUrl;
        private String apiKey;
        private int topN = 3;
        private int timeoutSeconds = 15;
        private double minRelevanceScore;
    }
}
