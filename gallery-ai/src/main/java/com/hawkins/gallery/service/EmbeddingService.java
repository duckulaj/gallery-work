package com.hawkins.gallery.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper mapper;

    @Cacheable("embeddings")
    public float[] embed(String text) {
        long start = System.currentTimeMillis();
        float[] result = embeddingModel.embed(text == null ? "" : text);
        log.info("Embedding generated in {}ms", System.currentTimeMillis() - start);
        return result;
    }

    public String toJson(float[] v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Converts a Java float array into the literal format accepted by pgvector.
     * Example: [0.123,-0.456,0.789]
     */
    public String toPgVectorLiteral(float[] v) {
        if (v == null || v.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(v.length * 12);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            if (Float.isFinite(v[i])) {
                sb.append(Float.toString(v[i]));
            } else {
                sb.append('0');
            }
        }
        sb.append(']');
        return sb.toString();
    }

    public float[] fromJson(String json) {
        try {
            java.util.List<Double> d = mapper.readValue(json, new TypeReference<>() {
            });
            float[] f = new float[d.size()];
            for (int i = 0; i < d.size(); i++)
                f[i] = d.get(i).floatValue();
            return f;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public float cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        return aa == 0 || bb == 0 ? 0f : (float) (dot / (Math.sqrt(aa) * Math.sqrt(bb)));
    }
}
