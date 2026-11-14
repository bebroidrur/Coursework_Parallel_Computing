package com.coursework.cli;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class HttpBackendGateway implements BackendGateway {
    private final URI baseUri;
    private final HttpClient http;
    private final tools.jackson.databind.ObjectMapper mapper;
    private final Duration timeout;

    public HttpBackendGateway(String baseUrl) {
        this(URI.create(normalize(baseUrl)), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                JsonMapper.builder().build(), Duration.ofSeconds(45));
    }

    HttpBackendGateway(URI baseUri, HttpClient http, tools.jackson.databind.ObjectMapper mapper, Duration timeout) {
        this.baseUri = baseUri; this.http = http; this.mapper = mapper; this.timeout = timeout;
    }

    @Override public JsonNode health() throws IOException, InterruptedException { return get("/api/health"); }
    @Override public JsonNode stats() throws IOException, InterruptedException { return get("/api/stats"); }
    @Override public JsonNode index(int threads) throws IOException, InterruptedException {
        return post("/api/index", Map.of("threads", threads));
    }
    @Override public JsonNode search(String query, String mode) throws IOException, InterruptedException {
        return post("/api/search", Map.of("query", query, "mode", mode));
    }

    @Override public void searchLive(String requestId, String query, String mode, Consumer<JsonNode> consumer)
            throws IOException, InterruptedException {
        URI wsUri = toWebSocketUri(baseUri.resolve("ws/search"));
        String payload = mapper.writeValueAsString(Map.of("requestId", requestId, "query", query, "mode", mode));
        CompletableFuture<Void> finished = new CompletableFuture<>();
        StringBuilder fragments = new StringBuilder();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
                webSocket.sendText(payload, true);
            }
            @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                fragments.append(data);
                if (last) {
                    try {
                        JsonNode event = mapper.readTree(fragments.toString());
                        fragments.setLength(0);
                        consumer.accept(event);
                        String stage = event.path("stage").asText();
                        if ("done".equals(stage) || "error".equals(stage)) {
                            finished.complete(null);
                            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "completed");
                        }
                    } catch (RuntimeException e) { finished.completeExceptionally(e); }
                }
                webSocket.request(1);
                return null;
            }
            @Override public void onError(WebSocket webSocket, Throwable error) { finished.completeExceptionally(error); }
        };
        try {
            http.newWebSocketBuilder().connectTimeout(timeout).buildAsync(wsUri, listener).join();
            finished.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (CompletionException e) {
            throw asIo(e.getCause() == null ? e : e.getCause());
        } catch (java.util.concurrent.ExecutionException e) {
            throw asIo(e.getCause() == null ? e : e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("WebSocket timeout after " + timeout.toSeconds() + " seconds", e);
        }
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout).GET().build();
        return execute(request);
    }
    private JsonNode post(String path, Map<String, ?> body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        return execute(request);
    }
    private JsonNode execute(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new BackendException(response.statusCode(), response.body().isBlank() ? "Backend HTTP " + response.statusCode() : response.body());
        return mapper.readTree(response.body());
    }
    private static String normalize(String value) { return value.endsWith("/") ? value : value + "/"; }
    private static URI toWebSocketUri(URI uri) {
        String scheme = "https".equalsIgnoreCase(uri.getScheme()) ? "wss" : "ws";
        return URI.create(scheme + "://" + uri.getAuthority() + uri.getPath());
    }
    private static IOException asIo(Throwable error) { return error instanceof IOException io ? io : new IOException(error.getMessage(), error); }
}
