package com.milanai.robot.utils;

import org.springframework.ai.document.Document;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Combines route ranks without mixing unrelated vector and BM25 score scales. */
public final class RrfFusion {
    private RrfFusion() { }

    public static List<Document> fuse(List<List<Document>> routes, int rrfK, int topK) {
        if (rrfK < 1 || topK < 1) {
            throw new IllegalArgumentException("RRF rank constant and pool size must be positive");
        }
        Map<String, Document> documents = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        for (List<Document> route : routes) {
            Set<String> seen = new HashSet<>();
            int rank = 0;
            for (Document document : route) {
                if (document == null || document.getId() == null || document.getId().isBlank()
                        || !seen.add(document.getId())) {
                    continue;
                }
                rank++;
                documents.putIfAbsent(document.getId(), document);
                scores.merge(document.getId(), 1.0 / ((double) rrfK + rank), Double::sum);
            }
        }
        return documents.keySet().stream()
                .sorted(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(id -> id))
                .limit(topK)
                .map(id -> documents.get(id).mutate().score(scores.get(id)).build())
                .toList();
    }
}
