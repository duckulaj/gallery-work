package com.hawkins.gallery.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.stereotype.Service;

import com.hawkins.gallery.config.AppProperties;
import com.hawkins.gallery.domain.Asset;
import com.hawkins.gallery.repository.AssetEmbeddingRepository;
import com.hawkins.gallery.repository.AssetRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class SearchService {
    private static final float TEXT_WEIGHT = 0.45f;
    private static final float SEMANTIC_WEIGHT = 0.55f;
    private static final float MIN_SEMANTIC_SCORE = 0.20f;
    private static final int DEFAULT_RESULT_LIMIT = 120;

    private final AssetRepository assets;
    private final AssetEmbeddingRepository embeddings;
    private final EmbeddingService embed;
    private final AppProperties props;
    private final QueryExpansionService queryExpansion;

    public List<Asset> search(String folderId, String q) {
        if (q == null || q.isBlank()) {
            return assets.findByFolderIdOrderByCreatedAtDesc(folderId);
        }

        String originalQuery = q.trim();
        String expandedQuery = queryExpansion.expand(originalQuery);
        Map<String, ScoredAsset> ranked = new LinkedHashMap<>();

        addTextMatches(expandedQuery, ranked);
        addSemanticMatches(expandedQuery, ranked);

        return ranked.values().stream()
                .sorted(Comparator.comparing(ScoredAsset::finalScore).reversed()
                        .thenComparing(hit -> hit.asset.getCreatedAt(), Comparator.reverseOrder()))
                .limit(DEFAULT_RESULT_LIMIT)
                .map(hit -> hit.asset)
                .toList();
    }

    private void addTextMatches(String expandedQuery, Map<String, ScoredAsset> ranked) {
        List<Asset> textMatches = assets.fullTextSearch(expandedQuery);
        for (int i = 0; i < textMatches.size(); i++) {
            Asset asset = textMatches.get(i);
            float positionScore = 1.0f - Math.min(0.40f, i / 500.0f);
            ranked.compute(asset.getId(), (id, existing) -> {
                ScoredAsset hit = existing == null ? new ScoredAsset(asset) : existing;
                hit.textScore = Math.max(hit.textScore, positionScore);
                return hit;
            });
        }
    }

    private void addSemanticMatches(String expandedQuery, Map<String, ScoredAsset> ranked) {
        try {
            float[] queryVector = embed.embed(expandedQuery);
            addPgVectorMatches(queryVector, ranked);
        } catch (Exception ex) {
            // Text search must remain useful even when Ollama/embedding generation is offline.
            log.debug("Semantic search skipped: {}", ex.getMessage());
        }
    }

    private void addPgVectorMatches(float[] queryVector, Map<String, ScoredAsset> ranked) {
        int limit = Math.max(DEFAULT_RESULT_LIMIT, props.semanticCandidateLimit());
        try {
            List<AssetEmbeddingRepository.SemanticAssetScoreRow> rows = embeddings.findNearest(
                    embed.toPgVectorLiteral(queryVector),
                    limit);

            List<String> ids = rows.stream().map(AssetEmbeddingRepository.SemanticAssetScoreRow::getAssetId).toList();
            Map<String, Asset> assetsById = StreamSupport.stream(assets.findAllById(ids).spliterator(), false)
                    .collect(Collectors.toMap(Asset::getId, Function.identity()));

            rows.stream()
                    .filter(row -> row.getSemanticScore() != null && row.getSemanticScore() > MIN_SEMANTIC_SCORE)
                    .forEach(row -> {
                        Asset asset = assetsById.get(row.getAssetId());
                        if (asset == null) {
                            return;
                        }
                        ranked.compute(asset.getId(), (id, existing) -> {
                            ScoredAsset hit = existing == null ? new ScoredAsset(asset) : existing;
                            hit.semanticScore = Math.max(hit.semanticScore, row.getSemanticScore());
                            return hit;
                        });
                    });
        } catch (Exception pgVectorEx) {
            // Compatibility path while moving from MySQL/JSON vectors to PostgreSQL/pgvector.
            log.debug("pgvector semantic search unavailable; using JSON fallback: {}", pgVectorEx.getMessage());
            addLegacyJsonSemanticMatches(queryVector, ranked);
        }
    }

    private void addLegacyJsonSemanticMatches(float[] queryVector, Map<String, ScoredAsset> ranked) {
        embeddings.findAllWithAsset().stream()
                .limit(props.semanticCandidateLimit())
                .map(e -> new SemanticHit(e.getAsset(), embed.cosine(queryVector, embed.fromJson(e.getEmbeddingJson()))))
                .filter(h -> h.score > MIN_SEMANTIC_SCORE)
                .forEach(h -> ranked.compute(h.asset.getId(), (id, existing) -> {
                    ScoredAsset hit = existing == null ? new ScoredAsset(h.asset) : existing;
                    hit.semanticScore = Math.max(hit.semanticScore, h.score);
                    return hit;
                }));
    }

    private static class ScoredAsset {
        private final Asset asset;
        private float textScore;
        private float semanticScore;

        private ScoredAsset(Asset asset) {
            this.asset = asset;
        }

        private float finalScore() {
            return (TEXT_WEIGHT * textScore) + (SEMANTIC_WEIGHT * semanticScore);
        }
    }

    private record SemanticHit(Asset asset, float score) {
    }
}
