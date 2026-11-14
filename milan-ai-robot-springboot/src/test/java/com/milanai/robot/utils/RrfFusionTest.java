package com.milanai.robot.utils;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RrfFusionTest {
    @Test
    void scoresUniqueRouteIdsByReciprocalRankAndRetainsMetadata() {
        Document a = doc("a", 500.0);
        Document b = doc("b", 0.01);

        List<Document> result = RrfFusion.fuse(List.of(List.of(a, a, b), List.of(b)), 60, 10);

        assertThat(result).extracting(Document::getId).containsExactly("b", "a");
        assertThat(result.getFirst().getScore()).isEqualTo(1.0 / 62 + 1.0 / 61);
        assertThat(result.get(1).getScore()).isEqualTo(1.0 / 61);
        assertThat(result.getFirst().getMetadata()).containsEntry("mdStorageId", 42L)
                .containsEntry("knowledge_base_id", "2");
        assertThat(b.getScore()).isEqualTo(0.01);
    }

    @Test
    void tiesUseDocumentIdsAndLimitTheFusedPool() {
        List<Document> result = RrfFusion.fuse(List.of(List.of(doc("z", 9)), List.of(doc("a", 1))), 60, 1);

        assertThat(result).extracting(Document::getId).containsExactly("a");
    }

    @Test
    void rejectsInvalidRankAndPoolSettings() {
        assertThatThrownBy(() -> RrfFusion.fuse(List.of(), 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RrfFusion.fuse(List.of(), 60, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private Document doc(String id, double score) {
        return Document.builder().id(id).text("Warranty " + id).score(score)
                .metadata(Map.of("mdStorageId", 42L, "knowledge_base_id", "2")).build();
    }
}
