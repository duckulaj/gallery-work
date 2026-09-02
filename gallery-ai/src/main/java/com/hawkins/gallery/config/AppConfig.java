package com.hawkins.gallery.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager ollamaConnectionManager(
            @Value("${app.ai.background.connection-pool-size:4}") int poolSize) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(poolSize);
        cm.setDefaultMaxPerRoute(poolSize);
        cm.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(10))
                .build());
        return cm;
    }

    /** HTTP client for Spring AI chat/embedding (/api/chat, /api/embed): 5 min response timeout. */
    @Bean(destroyMethod = "close")
    CloseableHttpClient ollamaApiHttpClient(PoolingHttpClientConnectionManager ollamaConnectionManager) {
        RequestConfig rc = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMinutes(5))
                .build();
        return HttpClients.custom()
                .setConnectionManager(ollamaConnectionManager)
                .setDefaultRequestConfig(rc)
                .evictExpiredConnections()
                .build();
    }

    @Bean
    RestClient ollamaRestClient(CloseableHttpClient ollamaApiHttpClient) {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(ollamaApiHttpClient))
                .build();
    }

    /**
     * Override Spring AI's auto-configured OllamaApi to extend the read timeout.
     * The default is 60 s which is too short for vision/chat models under load.
     * Spring AI's OllamaAutoConfiguration is @ConditionalOnMissingBean(OllamaApi.class)
     * so this bean takes precedence automatically.
     */
    @Bean
    OllamaApi ollamaApi(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            CloseableHttpClient ollamaApiHttpClient) {
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(ollamaApiHttpClient));
        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .build();
    }

    /**
     * Dedicated RestClient for the DeepFace face-detection microservice.
     * Uses a short connect timeout and a longer (60 s) read timeout since
     * ArcFace inference can take several seconds per image on CPU.
     */
    @Bean
    com.hawkins.gallery.service.DeepFaceClient deepFaceClient(
            AppProperties props,
            ObjectMapper objectMapper) {
        int poolSize = props.ai().background().connectionPoolSize();
        org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager cm =
                new org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager();
        cm.setMaxTotal(poolSize);
        cm.setDefaultMaxPerRoute(poolSize);
        cm.setDefaultConnectionConfig(
                org.apache.hc.client5.http.config.ConnectionConfig.custom()
                        .setConnectTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(5))
                        .build());
        org.apache.hc.client5.http.config.RequestConfig rc =
                org.apache.hc.client5.http.config.RequestConfig.custom()
                        .setResponseTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(60))
                        .build();
        org.apache.hc.client5.http.impl.classic.CloseableHttpClient http =
                org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                        .setConnectionManager(cm)
                        .setDefaultRequestConfig(rc)
                        .evictExpiredConnections()
                        .build();
        RestClient rc2 = RestClient.builder()
                .baseUrl(props.ai().faceRecognition().serviceUrl())
                .requestFactory(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(http))
                .build();
        return new com.hawkins.gallery.service.DeepFaceClient(rc2, objectMapper);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService enrichmentExecutor(
            @Value("${app.ai.background.worker-threads:${app.ai.background.embedding-threads:4}}") int threads,
            @Value("${app.ai.background.executor-queue-capacity:32}") int queueCapacity) {
        AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), r -> {
            Thread t = new Thread(r, "enrichment-" + counter.getAndIncrement());
            t.setDaemon(false);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
