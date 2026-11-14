package com.coursework.backend.ollama;
import org.junit.jupiter.api.*;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;

class OllamaServiceTests {
    HttpClient client;
    OllamaService service;
    @BeforeEach void setup() {
        client = mock(HttpClient.class);
        service = new OllamaService(new OllamaService.Settings("http://ollama:11434", "llama3.2:1b",
                Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(30), 3), client);
    }
    void reply(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(body);
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }
    String envelope(String content) { return JsonMapper.builder().build().writeValueAsString(java.util.Map.of("done", true, "response", content)); }
    @Test void normalizesDeduplicatesLimitsAndPreservesOriginals() {
        reply(200, envelope("{\"terms\":[\"JAVA\",\"Пошук\",\"пошук\",\"Index\",\"THREADS\",\"extra\"]}"));
        var result = service.expandQuery("Java original");
        assertEquals(List.of("java", "original", "пошук", "index", "threads"), result.terms());
        assertFalse(result.fallback());
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(request.capture(), any());
        assertEquals("http://ollama:11434/api/generate", request.getValue().uri().toString());
        assertEquals("POST", request.getValue().method());
        assertTrue(request.getValue().bodyPublisher().isPresent());
    }
    @Test void rejectsMalformedAndInvalidContent() {
        for (String body : List.of("bad", "{}", "[]", "{\"terms\":[]}", "{\"terms\":[42]}", "{\"terms\":[\"!!!\"]}", "{\"terms\":[\"a long explanation with too many words\"]}")) {
            reply(200, envelope(body));
            var result = service.expandQuery("ORIGINAL");
            assertTrue(result.fallback(), body); assertEquals("OLLAMA_INVALID_RESPONSE", result.warning());
            assertEquals(List.of("original"), result.terms());
        }
    }
    @Test void rejectsIncompleteEnvelope() {
        reply(200,"{\"done\":false,\"response\":\"{}\"}");
        assertTrue(service.expandQuery("java").fallback());
    }
    @Test void handlesHttpFailure() {
        reply(404,"{\"error\":\"model not found\"}");
        assertEquals("OLLAMA_HTTP_ERROR", service.expandQuery("java").warning());
    }
    @Test void handlesConnectionFailure() {
        when(client.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(CompletableFuture.failedFuture(new java.net.ConnectException()));
        assertEquals("OLLAMA_UNAVAILABLE", service.expandQuery("java").warning());
        assertFalse(service.isAvailable());
    }
    @Test void timeoutCancelsPendingRequest() {
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        when(client.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
        assertEquals("OLLAMA_TIMEOUT", service.expandQuery("java").warning());
        assertTrue(pending.isCancelled());
    }
    @Test void interruptionPreservesFlag() {
        when(client.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try { assertEquals("OLLAMA_INTERRUPTED", service.expandQuery("java").warning()); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }
    @Test void healthChecksTagsAndRejectsInvalidResponse() {
        reply(200,"{\"models\":[]}"); assertTrue(service.isAvailable());
        reply(200,"{}"); assertFalse(service.isAvailable());
        reply(500,"{}"); assertFalse(service.isAvailable());
    }
    @Test void healthTimeoutCancelsRequest() {
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        when(client.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
        assertFalse(service.isAvailable()); assertTrue(pending.isCancelled());
    }

    @Test void generationPayloadIsBoundedNonStreamingAndUsesSameModel() throws Exception {
        reply(200, envelope("{\"terms\":[\"threads\"]}"));
        service.expandQuery("parallel computing");
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(captured.capture(), any());
        var subscriber = HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8);
        captured.getValue().bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
            public void onNext(java.nio.ByteBuffer buffer) { subscriber.onNext(List.of(buffer)); }
            public void onError(Throwable error) { subscriber.onError(error); }
            public void onComplete() { subscriber.onComplete(); }
        });
        var payload = JsonMapper.builder().build().readTree(subscriber.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals("llama3.2:1b", payload.path("model").asString());
        assertFalse(payload.path("stream").asBoolean());
        assertEquals("json", payload.path("format").asString());
        assertEquals("5m", payload.path("keep_alive").asString());
        assertEquals(48, payload.path("options").path("num_predict").asInt());
        assertEquals(2048, payload.path("options").path("num_ctx").asInt());
        assertEquals("parallel computing", payload.path("prompt").asString());
        assertTrue(payload.path("system").asString().length() < 200);
    }
}
