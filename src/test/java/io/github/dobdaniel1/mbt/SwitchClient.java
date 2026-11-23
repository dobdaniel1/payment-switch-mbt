package io.github.dobdaniel1.mbt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Adapter from model steps to the switch's HTTP API. */
final class SwitchClient {

    record Response(int status, JsonNode body) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    // HTTP/1.1: the default HTTP/2 upgrade on plain http is rejected by uvicorn.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;

    SwitchClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    Response authorize(Map<String, Object> body) {
        return send("POST", "/v1/transactions", body);
    }

    Response get(String txnId) {
        return send("GET", "/v1/transactions/" + txnId, null);
    }

    Response list() {
        return send("GET", "/v1/transactions?limit=500", null);
    }

    Response reverse(String txnId) {
        return send("POST", "/v1/transactions/" + txnId + "/reversal", null);
    }

    Response retryReversals() {
        return send("POST", "/v1/reversals/retry", null);
    }

    Response closeBatch() {
        return send("POST", "/v1/settlement/batches", null);
    }

    Response setHost(String hostId, String status) {
        return send("PATCH", "/v1/hosts/" + hostId, Map.of("status", status));
    }

    Response hosts() {
        return send("GET", "/v1/hosts", null);
    }

    Response reset() {
        return send("POST", "/v1/admin/reset", null);
    }

    private Response send(String method, String path, Object body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .method(method, publisher)
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), parse(response.body()));
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + path + " failed; is the switch running at " + baseUrl + "?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode parse(String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (IOException notJson) {
            return TextNode.valueOf(text);
        }
    }
}
