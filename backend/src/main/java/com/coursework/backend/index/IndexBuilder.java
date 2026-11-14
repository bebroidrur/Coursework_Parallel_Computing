package com.coursework.backend.index;

import com.coursework.backend.document.Document;
import com.coursework.backend.text.Tokenizer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Builds are serialized; readers retain access to the previous complete index. */
public final class IndexBuilder {
    private final Function<String, List<String>> tokenize;
    private final int bucketCount;
    private volatile BuildSnapshot snapshot;

    /** One volatile publication keeps the index and its metadata consistent. */
    public record BuildSnapshot(InvertedIndex index, long elapsedNanos, int threads) {
        public double elapsedMillis() { return elapsedNanos / 1_000_000.0; }
    }

    public IndexBuilder() { this(new Tokenizer(), 1024); }
    public IndexBuilder(Tokenizer tokenizer, int bucketCount) {
        this(Objects.requireNonNull(tokenizer, "tokenizer")::tokenize, bucketCount);
    }
    // Injectable processing function for deterministic worker failure/interruption tests.
    IndexBuilder(Function<String, List<String>> tokenize, int bucketCount) {
        this.tokenize = Objects.requireNonNull(tokenize, "tokenize");
        this.bucketCount = bucketCount;
        snapshot = new BuildSnapshot(new InvertedIndex(bucketCount), 0, 1);
    }

    /** Sequential baseline, without an executor. */
    public synchronized void build(List<Document> documents) {
        buildInternal(documents, 1, false);
    }

    /** Explicit pool size; even N=1 uses an executor for a fair pool-overhead comparison. */
    public synchronized void buildParallel(List<Document> documents, int threads) {
        if (threads < 1) throw new IllegalArgumentException("threads must be positive");
        buildInternal(documents, threads, true);
    }

    private void buildInternal(List<Document> documents, int threads, boolean parallel) {
        long start = System.nanoTime();
        List<Document> input = List.copyOf(documents);
        InvertedIndex next = new InvertedIndex(bucketCount);
        for (Document document : input) {
            checkInterrupted();
            int before = next.getDocumentCount();
            next.registerDocument(document.getId());
            if (next.getDocumentCount() == before) {
                throw new IllegalArgumentException("Duplicate document id: " + document.getId());
            }
        }
        if (parallel) runWorkers(input, next, threads);
        else for (Document document : input) indexDocument(document, next);
        checkInterrupted();
        snapshot = new BuildSnapshot(next, System.nanoTime() - start, threads);
    }

    private void runWorkers(List<Document> documents, InvertedIndex next, int threads) {
        // Only N tasks are queued, rather than one Future for every document.
        int tasks = Math.min(threads, documents.size());
        if (tasks == 0) return;
        CustomThreadPool pool = new CustomThreadPool(threads);
        CompletionService<Void> completed = new java.util.concurrent.ExecutorCompletionService<>(pool);
        try {
            try {
                for (int worker = 0; worker < tasks; worker++) {
                    final int offset = worker;
                    completed.submit(() -> {
                        for (int i = offset; i < documents.size(); i += tasks) {
                            indexDocument(documents.get(i), next);
                        }
                        return null;
                    });
                }
                for (int i = 0; i < tasks; i++) completed.take().get();
                pool.shutdown();
                if (!pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IndexBuildException("Index worker pool did not terminate", new TimeoutException());
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                // Do not return to the caller until interrupted workers have
                // actually stopped. This keeps the snapshot transaction
                // atomic and prevents daemon workers from touching `next`
                // after the failed build has returned.
                try {
                    if (!pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IndexBuildException("Index worker pool did not terminate after interruption", new TimeoutException());
                    }
                } catch (InterruptedException stopping) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                    throw new IndexBuildException("Index build interrupted while stopping workers", stopping);
                }
                Thread.currentThread().interrupt();
                throw new IndexBuildException("Index build interrupted", e);
            } catch (ExecutionException e) {
                pool.shutdownNow();
                try { pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IndexBuildException("Index build interrupted while stopping workers", interrupted);
                }
                throw new IndexBuildException("Index worker failed", e.getCause());
            } finally {
                if (!pool.isShutdown()) pool.shutdownNow();
            }
        } catch (RuntimeException failure) {
            pool.shutdownNow();
            throw failure;
        }
    }

    private void indexDocument(Document document, InvertedIndex next) {
        checkInterrupted();
        for (String term : tokenize.apply(document.getContent())) {
            checkInterrupted();
            next.addRegisteredTerm(term, document.getId());
        }
    }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IndexBuildException("Index build interrupted", new InterruptedException());
        }
    }
    public BuildSnapshot getSnapshot() { return snapshot; }
    public InvertedIndex getIndex() { return snapshot.index(); }
    /** Backward-compatible nanosecond metric. */
    public long getLastBuildTime() { return snapshot.elapsedNanos(); }
    public double getLastBuildTimeMillis() { return snapshot.elapsedMillis(); }
    public int getThreadCount() { return snapshot.threads(); }
}
