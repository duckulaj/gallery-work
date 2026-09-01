package com.hawkins.gallery.review.service;

import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Service
public class NsfwClient {
    private final RestClient rest;

    public NsfwClient(RestClient.Builder builder,
                      @Value("${app.ai.nsfw.service-url:http://localhost:8082}") String baseUrl) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(90));
        this.rest = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public Result analyse(Path image) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new FileSystemResource(image));
        Result result = rest.post().uri("/nsfw/detect")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body).retrieve().body(Result.class);
        if (result == null) throw new IllegalStateException("NSFW service returned an empty response");
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(double score, String level, List<Label> labels, Integer scoringVersion, String error) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Label(String name, double score) { }
}
