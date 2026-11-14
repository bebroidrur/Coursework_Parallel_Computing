package com.coursework.backend.api;

import com.coursework.backend.search.SearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class RestApiTests {
    @Autowired WebApplicationContext context;
    @MockitoSpyBean SearchService service;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.coursework.backend.ollama.OllamaService ollama;
    @TempDir Path directory;
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        // Use a real service and real index for each test with an isolated dataset.
        SearchService real = new SearchService(directory.toString(), ollama);
        doAnswer(i -> real.index(i.getArgument(0))).when(service).index(any());
        doAnswer(i -> real.search(i.getArgument(0))).when(service).search(any());
        doAnswer(i -> real.stats()).when(service).stats();
    }
    void seed() throws Exception {
        Files.writeString(directory.resolve("a.txt"), "Java пошук JAVA");
        Files.writeString(directory.resolve("b.txt"), "Пошук документи");
        Files.writeString(directory.resolve("empty.txt"), "");
    }
    @Test void healthCompatible() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.ollama").value(false));
    }
    @Test void localFrontendOriginIsAllowedForRestApi() throws Exception {
        mvc.perform(get("/api/health").header("Origin", "http://localhost:4173"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4173"));
    }
    @Test void emptyIndexAndEmptyDataset() throws Exception {
        mvc.perform(get("/api/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.documents").value(0)).andExpect(jsonPath("$.unique_terms").value(0));
        mvc.perform(post("/api/search").contentType("application/json").content("{\"query\":\"java\",\"mode\":\"keyword\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results").isEmpty());
        mvc.perform(post("/api/index").contentType("application/json").content("{\"threads\":8}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.documents").value(0)).andExpect(jsonPath("$.threads_used").value(8));
    }
    @Test void indexSearchAndStats() throws Exception {
        seed();
        mvc.perform(post("/api/index").contentType("application/json").content("{\"threads\":4}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.documents").value(3)).andExpect(jsonPath("$.unique_terms").value(3))
            .andExpect(jsonPath("$.threads_used").value(4)).andExpect(jsonPath("$.index_build_time_ms").isNumber());
        mvc.perform(post("/api/search").contentType("application/json").content("{\"query\":\"JAVA java ПОШУК\",\"mode\":\"keyword\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.terms.length()").value(2))
            .andExpect(jsonPath("$.results[0].document_id").value("a.txt")).andExpect(jsonPath("$.results[0].score").value(2))
            .andExpect(jsonPath("$.results[0].snippet").value("Java пошук JAVA"));
        mvc.perform(get("/api/stats")).andExpect(jsonPath("$.unique_terms").value(3));
        mvc.perform(post("/api/search").contentType("application/json").content("{\"query\":\"missing\",\"mode\":\"keyword\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }
    @Test void rebuildReplacesTextAndTerms() throws Exception {
        seed(); mvc.perform(post("/api/index").contentType("application/json").content("{\"threads\":2}")).andExpect(status().isOk());
        Files.writeString(directory.resolve("a.txt"), "replacement");
        mvc.perform(post("/api/index").contentType("application/json").content("{\"threads\":1}")).andExpect(status().isOk());
        mvc.perform(post("/api/search").contentType("application/json").content("{\"query\":\"java\",\"mode\":\"keyword\"}"))
            .andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/search").contentType("application/json").content("{\"query\":\"replacement\",\"mode\":\"keyword\"}"))
            .andExpect(jsonPath("$.results[0].snippet").value("replacement"));
    }
    @Test void failedDatasetReadRetainsPreviousIndex() throws Exception {
        seed(); mvc.perform(post("/api/index").contentType("application/json").content("{\"threads\":2}")).andExpect(status().isOk());
        Files.write(directory.resolve("bad.txt"), new byte[]{(byte)0xc3, 0x28});
        mvc.perform(post("/api/index").contentType("application/json").content("{\"threads\":2}"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("DATASET_READ_FAILED"));
        mvc.perform(get("/api/stats")).andExpect(jsonPath("$.documents").value(3));
    }
    @Test void invalidThreads() throws Exception {
        for (String body : new String[]{"{}", "{\"threads\":0}", "{\"threads\":-1}", "{\"threads\":65}", "{\"threads\":null}", "{\"threads\":\"bad\"}", "{\"threads\":1.5}", "{\"threads\":\"2\"}"})
            mvc.perform(post("/api/index").contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value("error"));
    }
    @Test void invalidSearch() throws Exception {
        for (String body : new String[]{"{}", "{\"query\":\"java\"}", "{\"query\":\"java\",\"mode\":\"unsupported\"}", "{\"query\":\" \",\"mode\":\"keyword\"}", "{\"query\":\"!!!\",\"mode\":\"keyword\"}"})
            mvc.perform(post("/api/search").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/search").contentType("application/json").content("{\"query\":\""+"x".repeat(4097)+"\",\"mode\":\"keyword\"}")).andExpect(status().isBadRequest());
    }
    @Test void malformedBodiesAndHttpMethods() throws Exception {
        for (String body : new String[]{"", "null", "{", "[]"}) mvc.perform(post("/api/index").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/index")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("HTTP_405"));
        mvc.perform(post("/api/index").contentType("text/plain").content("hello")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/unknown")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HTTP_404"));
    }
    @Test void concurrentSearchAndReindexKeepResultsConsistent() throws Exception {
        Files.writeString(directory.resolve("one.txt"), "old");
        mvc.perform(post("/api/index").contentType("application/json").content("{\"threads\":2}")).andExpect(status().isOk());
        Files.writeString(directory.resolve("one.txt"), "new " + "new ".repeat(100000));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var rebuild = pool.submit(() -> mvc.perform(post("/api/index").contentType("application/json").content("{\"threads\":2}")).andExpect(status().isOk()));
            var searches = pool.submit(() -> {
                for (int i=0;i<30;i++) mvc.perform(post("/api/search").contentType("application/json").content("{\"query\":\"old new\",\"mode\":\"keyword\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.results[0].score").value(1));
                return null;
            });
            rebuild.get(15, TimeUnit.SECONDS); searches.get(15, TimeUnit.SECONDS);
        }
    }

    @Test void llmExpansionSearchAndStages() throws Exception {
        seed(); mvc.perform(post("/api/index").contentType("application/json").content("{\"threads\":2}")).andExpect(status().isOk());
        when(ollama.expandQuery("software")).thenReturn(new com.coursework.backend.ollama.OllamaService.Expansion(java.util.List.of("software", "java"), false, null));
        mvc.perform(post("/api/search").contentType("application/json").content("{\"query\":\"software\",\"mode\":\"llm\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.mode").value("llm"))
            .andExpect(jsonPath("$.fallback").value(false)).andExpect(jsonPath("$.terms[0]").value("software"))
            .andExpect(jsonPath("$.stages[0]").value("query_expansion")).andExpect(jsonPath("$.stages[1]").value("keyword_search"))
            .andExpect(jsonPath("$.stages[2]").value("done"));
    }
    @Test void llmFallbackStillSearchesOriginalTerms() throws Exception {
        seed(); mvc.perform(post("/api/index").contentType("application/json").content("{\"threads\":2}")).andExpect(status().isOk());
        when(ollama.expandQuery("java")).thenReturn(new com.coursework.backend.ollama.OllamaService.Expansion(java.util.List.of("java"), true, "OLLAMA_TIMEOUT"));
        mvc.perform(post("/api/search").contentType("application/json").content("{\"query\":\"java\",\"mode\":\"llm\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.fallback").value(true))
            .andExpect(jsonPath("$.warning").value("OLLAMA_TIMEOUT"));
    }
    @Test void keywordNeverCallsOllama() throws Exception {
        mvc.perform(post("/api/search").contentType("application/json").content("{\"query\":\"java\",\"mode\":\"keyword\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.fallback").value(false));
        verifyNoInteractions(ollama);
    }
    @Test void healthReflectsOllamaAvailability() throws Exception {
        when(ollama.isAvailable()).thenReturn(true);
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.ollama").value(true));
    }
}
