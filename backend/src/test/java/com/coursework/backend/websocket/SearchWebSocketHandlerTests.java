package com.coursework.backend.websocket;

import com.coursework.backend.dto.*;
import com.coursework.backend.search.SearchProgressListener;
import com.coursework.backend.search.SearchService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchWebSocketHandlerTests {
    SearchService search;
    SearchWebSocketHandler handler;
    WebSocketSession session;
    List<String> payloads;

    @BeforeEach void setup() throws Exception {
        search = mock(SearchService.class); session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1"); when(session.isOpen()).thenReturn(true);
        payloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            SearchProgressListener listener = invocation.getArgument(1);
            SearchRequest request = invocation.getArgument(0);
            listener.queryExpansion(List.of("keyword", "expanded"), false, null);
            listener.keywordSearch(1);
            listener.done(new SearchResponse("ok", request.query(), request.mode(), List.of("keyword", "expanded"), 1,
                    List.of(new SearchResult("doc.txt", 2, "snippet")), List.of("query_expansion", "keyword_search", "done"), false, null));
            return null;
        }).when(search).search(any(SearchRequest.class), any(SearchProgressListener.class));
        handler = new SearchWebSocketHandler(search, JsonMapper.builder().build());
        doAnswer(invocation -> { payloads.add(((TextMessage) invocation.getArgument(0)).getPayload()); return null; })
                .when(session).sendMessage(any(TextMessage.class));
        handler.afterConnectionEstablished(session);
    }
    void send(String json) throws Exception {
        // The handler runs search asynchronously: LLM has started, expansion,
        // keyword_search and done; wait for all four before asserting.
        int expected = payloads.size() + (json.contains("\"mode\":\"llm\"") ? 4 : 3);
        handler.handleMessage(session, new TextMessage(json));
        awaitMessages(expected);
    }
    void awaitMessages(int count) throws InterruptedException {
        for (int i = 0; i < 100 && payloads.size() < count; i++) Thread.sleep(10);
    }
    @Test void keywordEmitsStartedKeywordSearchAndDone() throws Exception {
        doAnswer(invocation -> {
            SearchProgressListener listener = invocation.getArgument(1);
            SearchRequest request = invocation.getArgument(0);
            listener.keywordSearch(1); listener.done(new SearchResponse("ok", request.query(), "keyword", List.of("java"), 1,
                    List.of(), List.of("keyword_search", "done"), false, null)); return null;
        }).when(search).search(any(SearchRequest.class), any(SearchProgressListener.class));
        send("{\"requestId\":\"r1\",\"query\":\"java\",\"mode\":\"keyword\"}");
        assertEquals(3, payloads.size());
        assertTrue(payloads.get(0).contains("\"stage\":\"started\""));
        assertTrue(payloads.get(1).contains("\"stage\":\"keyword_search\""));
        assertTrue(payloads.get(2).contains("\"stage\":\"done\""));
        assertTrue(payloads.stream().noneMatch(p -> p.contains("query_expansion")));
        assertTrue(payloads.stream().allMatch(p -> p.contains("\"requestId\":\"r1\"") && p.contains("timestamp")));
    }
    @Test void llmEmitsExpansionAndDoneWithRestResponse() throws Exception {
        send("{\"requestId\":\"r2\",\"query\":\"keyword\",\"mode\":\"llm\"}");
        assertEquals(4, payloads.size(), payloads.toString());
        assertTrue(payloads.get(1).contains("query_expansion") && payloads.get(1).contains("expanded"));
        assertTrue(payloads.get(3).contains("results") && payloads.get(3).contains("doc.txt"));
    }
    @Test void malformedRequestProducesErrorWithoutCallingSearch() throws Exception {
        handler.handleMessage(session, new TextMessage("{\"query\":\"java\",\"mode\":\"keyword\"}"));
        awaitMessages(1);
        assertEquals(1, payloads.size()); assertTrue(payloads.getFirst().contains("INVALID_MESSAGE"));
        verifyNoInteractions(search);
    }
    @Test void searchFailureProducesError() throws Exception {
        reset(search);
        doAnswer(invocation -> { invocation.<SearchProgressListener>getArgument(1).error(new RuntimeException("hidden")); return null; })
                .when(search).search(any(SearchRequest.class), any(SearchProgressListener.class));
        send("{\"requestId\":\"r3\",\"query\":\"java\",\"mode\":\"keyword\"}");
        assertTrue(payloads.stream().anyMatch(p -> p.contains("SEARCH_ERROR")));
        assertFalse(payloads.stream().anyMatch(p -> p.contains("hidden")));
    }
    @Test void requestsOnSameConnectionRemainIdentified() throws Exception {
        send("{\"requestId\":\"a\",\"query\":\"one\",\"mode\":\"keyword\"}");
        send("{\"requestId\":\"b\",\"query\":\"two\",\"mode\":\"keyword\"}");
        assertTrue(payloads.stream().filter(p -> p.contains("\"requestId\":\"a\"")).count() >= 3);
        assertTrue(payloads.stream().filter(p -> p.contains("\"requestId\":\"b\"")).count() >= 3);
    }
    @Test void closedConnectionCancelsOutstandingWork() throws Exception {
        reset(search); CountDownLatch started = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> { started.countDown(); release.await(); return null; }).when(search)
                .search(any(SearchRequest.class), any(SearchProgressListener.class));
        handler.handleMessage(session, new TextMessage("{\"requestId\":\"r4\",\"query\":\"java\",\"mode\":\"llm\"}"));
        assertTrue(started.await(1, TimeUnit.SECONDS)); handler.afterConnectionClosed(session, CloseStatus.NORMAL); release.countDown();
        verify(search, timeout(1000)).search(any(SearchRequest.class), any(SearchProgressListener.class));
    }
}
