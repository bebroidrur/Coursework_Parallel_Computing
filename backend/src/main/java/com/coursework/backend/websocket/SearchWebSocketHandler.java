package com.coursework.backend.websocket;

import com.coursework.backend.dto.SearchRequest;
import com.coursework.backend.dto.SearchResponse;
import com.coursework.backend.error.ApiException;
import com.coursework.backend.search.SearchProgressListener;
import com.coursework.backend.search.SearchService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SearchWebSocketHandler extends TextWebSocketHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SearchWebSocketHandler.class);
    private final SearchService searchService;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "ws-search"); thread.setDaemon(true); return thread;
    });
    private final Map<String, Set<Future<?>>> sessions = new ConcurrentHashMap<>();

    public SearchWebSocketHandler(SearchService searchService, ObjectMapper mapper) {
        this.searchService = searchService; this.mapper = mapper;
    }

    @Override public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), ConcurrentHashMap.newKeySet());
    }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WsRequest request;
        try {
            request = mapper.readValue(message.getPayload(), WsRequest.class);
            validate(request);
        } catch (Exception e) {
            sendError(session, null, "INVALID_MESSAGE", "Повідомлення має містити requestId, query і mode keyword або llm");
            return;
        }
        String requestId = request.requestId();
        send(session, new WsEvent(requestId, "started", Map.of("query", request.query(), "mode", request.mode())));
        SearchProgressListener listener = new SearchProgressListener() {
            public void queryExpansion(List<String> terms, boolean fallback, String warning) {
                Map<String,Object> data = new LinkedHashMap<>(); data.put("terms", terms); data.put("fallback", fallback); data.put("warning", warning);
                send(session, new WsEvent(requestId, "query_expansion", data));
            }
            public void keywordSearch(int resultCount) { send(session, new WsEvent(requestId, "keyword_search", Map.of("resultCount", resultCount))); }
            public void done(SearchResponse response) {
                Map<String,Object> data = new LinkedHashMap<>();
                data.put("status", response.status()); data.put("query", response.query()); data.put("mode", response.mode());
                data.put("terms", response.terms()); data.put("total", response.total()); data.put("results", response.results());
                data.put("stages", response.stages()); data.put("fallback", response.fallback()); data.put("warning", response.warning());
                send(session, new WsEvent(requestId, "done", data));
            }
            public void error(Throwable error) { sendError(session, requestId, "SEARCH_ERROR", safeMessage(error)); }
        };
        Future<?> future = executor.submit(() -> {
            try { searchService.search(new SearchRequest(request.query(), request.mode()), listener); }
            finally { sessions.getOrDefault(session.getId(), Set.of()).removeIf(Future::isDone); }
        });
        sessions.computeIfAbsent(session.getId(), ignored -> ConcurrentHashMap.newKeySet()).add(future);
    }
    private void validate(WsRequest request) {
        if (request == null || request.requestId() == null || request.requestId().isBlank() || request.requestId().length() > 128
                || request.query() == null || request.query().isBlank() || request.query().length() > 4096
                || (!"keyword".equals(request.mode()) && !"llm".equals(request.mode()))) throw new IllegalArgumentException();
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Set<Future<?>> work = sessions.remove(session.getId());
        if (work != null) work.forEach(f -> f.cancel(true));
    }
    @Override public void handleTransportError(WebSocketSession session, Throwable exception) { afterConnectionClosed(session, CloseStatus.SERVER_ERROR); }
    private void send(WebSocketSession session, WsEvent event) {
        if (!session.isOpen()) return;
        try { synchronized (session) { if (session.isOpen()) session.sendMessage(new TextMessage(mapper.writeValueAsString(event))); } }
        catch (Exception e) { LOG.warn("WebSocket message serialization/send failed", e); afterConnectionClosed(session, CloseStatus.SERVER_ERROR); }
    }
    private void sendError(WebSocketSession session, String requestId, String code, String message) {
        send(session, new WsEvent(requestId, "error", Map.of("code", code, "message", message)));
    }
    private String safeMessage(Throwable error) { return error instanceof ApiException ? error.getMessage() : "Пошук не вдалося завершити"; }
    public record WsRequest(String requestId, String query, String mode) {}
    public record WsEvent(String requestId, String stage, Instant timestamp, Object data) {
        WsEvent(String requestId, String stage, Object data) { this(requestId, stage, Instant.now(), data); }
    }
}
