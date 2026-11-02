package com.shijie.resumehelper.job.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Bounded public API transport. Endpoints are server-controlled, never supplied by the model. */
public class PublicJobJsonClient {
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public JsonNode get(URI uri) throws Exception {
        return send(HttpRequest.newBuilder(uri).GET().header("Referer", "https://careers.tencent.com/").build());
    }

    public JsonNode post(URI uri, String apiKey, Object body) throws Exception {
        return send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8)).build());
    }

    public JsonNode post(URI uri, Object body) throws Exception {
        return send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .header("Referer", uri.resolve("/").toString())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8)).build());
    }

    private JsonNode send(HttpRequest source) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(source.uri()).timeout(Duration.ofSeconds(8))
                .headers("Accept", "application/json", "User-Agent", "ShiJie-Career-Studio/1.0")
                .method(source.method(), source.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
                .build();
        var builder = HttpRequest.newBuilder(request, (name, value) -> true);
        source.headers().map().forEach((key, values) -> values.forEach(value -> builder.header(key, value)));
        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("岗位来源返回 HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalStateException("岗位来源返回内容过大");
            return mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        }
    }
}
