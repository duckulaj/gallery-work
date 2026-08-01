package com.hawkins.gallery.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QueryExpansionService {
    private static final int MAX_TERMS = 12;

    private final ChatClient chatClient;

    @Cacheable(value = "query-expansions", unless = "#result.isBlank()")
    public String expand(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        Set<String> terms = new LinkedHashSet<>(tokenise(query));
        terms.addAll(localSynonyms(query));

        try {
            // Sanitise to prevent prompt injection via crafted search queries
            String safeQuery = query.trim().replaceAll("[\\r\\n\\t]", " ");
            String prompt = """
                    Expand this photo-gallery search query into concise searchable words.
                    Include object synonyms, common colour spelling variants, face/person descriptors, scene labels, and related image tags.
                    Return comma-separated words only. Do not explain.

                    Query: %s
                    """.formatted(safeQuery);
            String response = CompletableFuture
                    .supplyAsync(() -> chatClient.prompt().user(prompt).call().content())
                    .orTimeout(4, TimeUnit.SECONDS)
                    .join();
            if (response != null && !response.isBlank()) {
                terms.addAll(tokenise(response));
            }
        } catch (Exception ignored) {
            // Search must remain fast and robust when the local chat model is offline or slow.
        }

        String expanded = terms.stream()
                .filter(s -> !s.isBlank())
                .limit(MAX_TERMS)
                .collect(Collectors.joining(" "));
        return expanded.isBlank() ? query.trim() : expanded;
    }

    private Set<String> tokenise(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .map(String::trim)
                .filter(s -> s.length() > 1)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> localSynonyms(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        Set<String> synonyms = new LinkedHashSet<>();
        if (q.contains("motorbike") || q.contains("motorcycle") || q.contains("bike")) {
            synonyms.addAll(Lists.of("motorcycle", "motorbike", "bike", "vehicle"));
        }
        if (q.contains("car") || q.contains("automobile")) {
            synonyms.addAll(Lists.of("car", "automobile", "vehicle"));
        }
        if (q.contains("person") || q.contains("people") || q.contains("face") || q.contains("portrait")) {
            synonyms.addAll(Lists.of("person", "people", "face", "faces", "portrait"));
        }
        if (q.contains("indoor") || q.contains("inside")) {
            synonyms.addAll(Lists.of("indoor", "inside", "interior"));
        }
        if (q.contains("outdoor") || q.contains("outside")) {
            synonyms.addAll(Lists.of("outdoor", "outside", "exterior"));
        }
        if (q.contains("grey")) {
            synonyms.add("gray");
        }
        if (q.contains("gray")) {
            synonyms.add("grey");
        }
        return synonyms;
    }

    private static final class Lists {
        private static Set<String> of(String... values) {
            return new LinkedHashSet<>(Arrays.asList(values));
        }
    }
}
