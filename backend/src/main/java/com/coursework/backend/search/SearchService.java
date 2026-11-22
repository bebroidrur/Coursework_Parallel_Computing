package com.coursework.backend.search;

import com.coursework.backend.document.*;
import com.coursework.backend.ollama.OllamaService;
import com.coursework.backend.index.*;
import com.coursework.backend.text.Tokenizer;
import com.coursework.backend.dto.*;
import com.coursework.backend.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Owns the builder; API readers only receive completed index/document snapshots. */
@Service
public class SearchService {
    private final IndexBuilder builder = new IndexBuilder();
    private final DatasetLoader loader = new DatasetLoader();
    private final Tokenizer tokenizer = new Tokenizer();
    private final Path dataset;
    private final OllamaService ollama;
    private record State(IndexBuilder.BuildSnapshot build, List<DocumentRef> documents, int uniqueTerms) {}
    private volatile State state = new State(builder.getSnapshot(), List.of(), 0);

    public SearchService(@Value("${search.dataset-path:datasets/sample}") String datasetPath, OllamaService ollama) {
        dataset = DatasetPathResolver.resolve(datasetPath);
        this.ollama = ollama;
    }
    public synchronized StatsResponse index(IndexRequest request) {
        if (request.threads() == null || request.threads() < 1 || request.threads() > 64)
            throw bad("threads має бути цілим числом від 1 до 64");
        try {
            List<DocumentRef> documents = loader.loadRefs(dataset);
            builder.buildParallelRefs(documents, request.threads());
            var build = builder.getSnapshot();
            State next = new State(build, documents, build.index().getUniqueTermCount());
            state = next;
            return stats(next);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DATASET_READ_FAILED", "Не вдалося прочитати налаштований UTF-8 датасет");
        } catch (IndexBuildException e) {
            if (hasIoCause(e)) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DATASET_READ_FAILED", "Не вдалося прочитати налаштований UTF-8 датасет");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INDEX_BUILD_FAILED", "Індексацію перервано або сталася помилка worker; попередній індекс збережено");
        }
    }
    public StatsResponse stats() { return stats(state); }
    private StatsResponse stats(State current) {
        return new StatsResponse("ok", current.build().index().getDocumentCount(), current.uniqueTerms(),
                current.build().elapsedMillis(), current.build().threads());
    }
    public SearchResponse search(SearchRequest request) {
        return search(request, SearchProgressListener.NONE);
    }
    public SearchResponse search(SearchRequest request, SearchProgressListener listener) {
        Objects.requireNonNull(listener, "listener");
        try {
            return searchInternal(request, listener);
        } catch (RuntimeException e) {
            listener.error(e);
            throw e;
        }
    }
    private SearchResponse searchInternal(SearchRequest request, SearchProgressListener listener) {
        if (!"keyword".equals(request.mode()) && !"llm".equals(request.mode())) throw bad("mode має дорівнювати keyword або llm");
        if (request.query() == null || request.query().isBlank() || request.query().length() > 4096)
            throw bad("query має містити від 1 до 4096 символів і не бути порожнім");
        List<String> terms = tokenizer.tokenize(request.query()).stream().distinct().toList();
        if (terms.isEmpty()) throw bad("query має містити хоча б одну літеру або цифру");
        List<String> stages = new ArrayList<>();
        boolean fallback = false;
        String warning = null;
        if ("llm".equals(request.mode())) {
            stages.add("query_expansion");
            var expanded = ollama.expandQuery(request.query());
            terms = expanded.terms(); fallback = expanded.fallback(); warning = expanded.warning();
            listener.queryExpansion(terms, fallback, warning);
        }
        stages.add("keyword_search");
        State current = state; // capture once: index and snippets always belong to the same build
        List<Set<String>> postings = terms.stream().map(current.build().index()::search).toList();
        List<SearchResult> results = new ArrayList<>();
        for (DocumentRef doc : current.documents()) {
            int score = 0;
            for (Set<String> ids : postings) if (ids.contains(doc.getId())) score++;
            if (score > 0) {
                results.add(new SearchResult(doc.getId(), score, readSnippet(doc)));
            }
        }
        results.sort(Comparator.comparingInt(SearchResult::score).reversed().thenComparing(SearchResult::document_id));
        listener.keywordSearch(results.size());
        stages.add("done");
        SearchResponse response = new SearchResponse("ok", request.query(), request.mode(), terms, results.size(), List.copyOf(results), List.copyOf(stages), fallback, warning);
        listener.done(response);
        return response;
    }
    private static String readSnippet(DocumentRef document) {
        StringBuilder content = new StringBuilder(256);
        try (Reader reader = Files.newBufferedReader(document.getPath(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[512];
            int read;
            while (content.codePointCount(0, content.length()) < 200
                    && (read = reader.read(buffer)) >= 0) {
                content.append(buffer, 0, read);
            }
            int end = content.offsetByCodePoints(0, Math.min(200, content.codePointCount(0, content.length())));
            return content.substring(0, end);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT_READ_FAILED", "Не вдалося прочитати документ для формування snippet");
        }
    }
    private static boolean hasIoCause(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof IOException) return true;
        }
        return false;
    }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message); }
}
