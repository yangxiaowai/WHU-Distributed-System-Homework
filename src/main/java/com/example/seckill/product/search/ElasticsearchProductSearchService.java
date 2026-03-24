package com.example.seckill.product.search;

import com.example.seckill.product.domain.Product;
import com.example.seckill.product.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ElasticsearchProductSearchService {

    private final ProductService productService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Value("${seckill.search.es.enabled:true}")
    private boolean enabled;

    @Value("${seckill.search.es.base-url:http://elasticsearch:9200}")
    private String baseUrl;

    private static final String INDEX = "products";

    public ElasticsearchProductSearchService(ProductService productService, ObjectMapper objectMapper) {
        this.productService = productService;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupIndex() {
        if (!enabled) {
            return;
        }
        try {
            ensureIndex();
            indexAllProducts();
        } catch (Exception ignored) {
            // ES 是可选项：不可用时不影响主业务
        }
    }

    public SearchResponse search(String q, int size) throws Exception {
        if (!enabled) {
            throw new IllegalStateException("Elasticsearch is disabled");
        }
        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("q 不能为空");
        }
        int finalSize = Math.max(1, Math.min(size, 50));
        String body = objectMapper.writeValueAsString(Map.of(
                "size", finalSize,
                "query", Map.of(
                        "multi_match", Map.of(
                                "query", q,
                                "fields", List.of("name^3", "description")
                        )
                )
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + INDEX + "/_search"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("ES search failed: " + resp.statusCode() + " " + resp.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.readValue(resp.body(), Map.class);
        return parseSearchResponse(map);
    }

    public void reindexAll() throws Exception {
        if (!enabled) {
            throw new IllegalStateException("Elasticsearch is disabled");
        }
        ensureIndex();
        indexAllProducts();
    }

    private void ensureIndex() throws Exception {
        HttpRequest head = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + INDEX))
                .timeout(Duration.ofSeconds(2))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> headResp = httpClient.send(head, HttpResponse.BodyHandlers.discarding());
        if (headResp.statusCode() == 200) {
            return;
        }

        String mapping = objectMapper.writeValueAsString(Map.of(
                "mappings", Map.of(
                        "properties", Map.of(
                                "id", Map.of("type", "long"),
                                "name", Map.of("type", "text"),
                                "description", Map.of("type", "text"),
                                "price", Map.of("type", "double"),
                                "status", Map.of("type", "integer")
                        )
                )
        ));

        HttpRequest put = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + INDEX))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapping))
                .build();
        HttpResponse<String> putResp = httpClient.send(put, HttpResponse.BodyHandlers.ofString());
        if (putResp.statusCode() >= 400) {
            throw new IllegalStateException("ES create index failed: " + putResp.statusCode() + " " + putResp.body());
        }
    }

    private void indexAllProducts() throws Exception {
        List<Product> products = productService.listAllProducts();
        if (products.isEmpty()) {
            return;
        }

        StringBuilder bulk = new StringBuilder();
        for (Product p : products) {
            bulk.append("{\"index\":{\"_index\":\"").append(INDEX).append("\",\"_id\":\"").append(p.getId()).append("\"}}\n");
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("id", p.getId());
            document.put("name", p.getName());
            document.put("description", p.getDescription());
            document.put("price", p.getPrice());
            document.put("status", p.getStatus());
            bulk.append(objectMapper.writeValueAsString(document)).append("\n");
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/_bulk?refresh=true"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(bulk.toString()))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("ES bulk index failed: " + resp.statusCode() + " " + resp.body());
        }
    }

    @SuppressWarnings("unchecked")
    private SearchResponse parseSearchResponse(Map<String, Object> map) {
        Object hitsObj = map.get("hits");
        if (!(hitsObj instanceof Map<?, ?> hitsMap)) {
            return new SearchResponse(0, List.of());
        }

        long total = 0;
        Object totalObj = ((Map<String, Object>) hitsMap).get("total");
        if (totalObj instanceof Map<?, ?> totalMap) {
            Object value = ((Map<String, Object>) totalMap).get("value");
            if (value instanceof Number n) {
                total = n.longValue();
            }
        } else if (totalObj instanceof Number n) {
            total = n.longValue();
        }

        Object listObj = ((Map<String, Object>) hitsMap).get("hits");
        if (!(listObj instanceof List<?> list)) {
            return new SearchResponse(total, List.of());
        }

        List<ProductSearchHit> items = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> hit)) {
                continue;
            }
            Map<String, Object> hitMap = (Map<String, Object>) hit;
            Object sourceObj = hitMap.get("_source");
            if (!(sourceObj instanceof Map<?, ?> source)) {
                continue;
            }
            Product product = objectMapper.convertValue(source, Product.class);
            Object scoreObj = hitMap.get("_score");
            double score = scoreObj instanceof Number n ? n.doubleValue() : 0.0;
            if (product != null && Objects.nonNull(product.getId())) {
                items.add(new ProductSearchHit(product, score));
            }
        }
        return new SearchResponse(total, items);
    }

    public record SearchResponse(long total, List<ProductSearchHit> items) {}
}
