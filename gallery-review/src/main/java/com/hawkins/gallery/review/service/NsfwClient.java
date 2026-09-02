package com.hawkins.gallery.review.service;

import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hawkins.gallery.config.AppProperties;
import jakarta.annotation.PreDestroy;

@Service
public class NsfwClient {
    private final RestClient rest;
    private final CloseableHttpClient httpClient;

    public NsfwClient(RestClient.Builder builder,
                      AppProperties props,
                      @Value("${app.ai.nsfw.service-url:http://localhost:8082}") String baseUrl) {
        int poolSize = props.ai().background().connectionPoolSize();
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(poolSize);
        cm.setDefaultMaxPerRoute(poolSize);
        cm.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .build());
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(90))
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .build();
        this.rest = builder.baseUrl(baseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
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

    @PreDestroy
    void close() throws java.io.IOException {
        httpClient.close();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(double score, String level, List<Label> labels, Integer scoringVersion, String error) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Label(String name, double score) { }
}
