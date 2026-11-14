package com.coursework.cli;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CliRunnerTests {
    private final JsonNode ok = JsonMapper.builder().build().readTree("{\"status\":\"ok\",\"documents\":4,\"unique_terms\":38,\"index_build_time_ms\":2.5,\"threads_used\":4}");

    @Test void healthIndexStatsAndRestSearchUseGateway() {
        FakeGateway gateway = new FakeGateway();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CliRunner runner = new CliRunner(gateway, new PrintStream(bytes));

        assertEquals(0, runner.run(new String[]{"health"}));
        assertEquals(0, runner.run(new String[]{"index", "--threads", "8"}));
        assertEquals(0, runner.run(new String[]{"stats"}));
        assertEquals(0, runner.run(new String[]{"search", "--query", "parallel computing", "--mode", "llm"}));

        assertEquals(8, gateway.threads);
        assertEquals("parallel computing", gateway.query);
        assertEquals("llm", gateway.mode);
        assertTrue(bytes.toString().contains("unique_terms"));
    }

    @Test void liveSearchPrintsAllStagesAndKeepsWarnings() {
        FakeGateway gateway = new FakeGateway();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = new CliRunner(gateway, new PrintStream(bytes)).run(
                new String[]{"search", "--query", "java", "--mode", "llm", "--live"});
        String output = bytes.toString();
        assertEquals(0, code);
        assertTrue(output.contains("[started]"));
        assertTrue(output.contains("[query_expansion]"));
        assertTrue(output.contains("[keyword_search]"));
        assertTrue(output.contains("[done]"));
        assertTrue(output.contains("OLLAMA_TIMEOUT"));
    }

    @Test void invalidArgumentsDoNotThrowAndReturnUsageError() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = new CliRunner(new FakeGateway(), new PrintStream(bytes)).run(
                new String[]{"search", "--mode", "invalid"});
        assertEquals(2, code);
        assertTrue(bytes.toString().contains("mode має бути keyword або llm") || bytes.toString().contains("для search потрібен"));
    }

    @Test void backendFailureIsReportedAsUserFacingError() {
        FakeGateway gateway = new FakeGateway(); gateway.fail = true;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = new CliRunner(gateway, new PrintStream(bytes)).run(new String[]{"stats"});
        assertEquals(3, code);
        assertTrue(bytes.toString().contains("HTTP 503"));
    }

    private static final class FakeGateway implements BackendGateway {
        final JsonNode response = JsonMapper.builder().build().readTree("{\"status\":\"ok\",\"documents\":4,\"unique_terms\":38,\"total\":1,\"results\":[{\"document_id\":\"01-parallel.txt\",\"score\":2,\"snippet\":\"parallel\"}]}");
        int threads; String query; String mode; boolean fail;
        private void maybeFail() throws BackendException { if (fail) throw new BackendException(503, "service unavailable"); }
        public JsonNode health() throws IOException { maybeFail(); return response; }
        public JsonNode index(int threads) throws IOException { maybeFail(); this.threads = threads; return response; }
        public JsonNode search(String query, String mode) throws IOException { maybeFail(); this.query = query; this.mode = mode; return response; }
        public JsonNode stats() throws IOException { maybeFail(); return response; }
        public void searchLive(String requestId, String query, String mode, Consumer<JsonNode> consumer) throws IOException {
            maybeFail(); this.query = query; this.mode = mode;
            var parser = JsonMapper.builder().build();
            for (String event : List.of(
                    "{\"requestId\":\"r\",\"stage\":\"started\",\"timestamp\":\"now\",\"data\":{}}",
                    "{\"requestId\":\"r\",\"stage\":\"query_expansion\",\"timestamp\":\"now\",\"data\":{\"fallback\":true,\"warning\":\"OLLAMA_TIMEOUT\"}}",
                    "{\"requestId\":\"r\",\"stage\":\"keyword_search\",\"timestamp\":\"now\",\"data\":{\"resultCount\":1}}",
                    "{\"requestId\":\"r\",\"stage\":\"done\",\"timestamp\":\"now\",\"data\":{\"total\":1}}")) consumer.accept(parser.readTree(event));
        }
    }
}
