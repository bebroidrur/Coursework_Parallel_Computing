package com.coursework.backend.index;

import com.coursework.backend.document.Document;
import com.coursework.backend.text.Tokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ParallelIndexTests {
    static List<Document> corpus(int count) {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) docs.add(new Document("doc" + i,
                i % 17 == 0 ? "" : "Java JAVA пошук term" + (i % 31), "doc" + i));
        return docs;
    }
    static void assertEquivalent(List<Document> docs, InvertedIndex a, InvertedIndex b) {
        Set<String> terms = new TreeSet<>();
        for (Document doc : docs) terms.addAll(new Tokenizer().tokenize(doc.getContent()));
        terms.add("absent");
        assertEquals(a.getDocumentCount(), b.getDocumentCount());
        for (String term : terms) assertEquals(a.search(term), b.search(term), term);
    }
    @Test void matchesSequentialWithShuffledInputAndCollisions() {
        List<Document> docs = corpus(600);
        IndexBuilder baseline = new IndexBuilder(new Tokenizer(), 1); baseline.build(docs);
        for (int n : new int[]{1, 2, 4, 8}) {
            List<Document> shuffled = new ArrayList<>(docs); Collections.shuffle(shuffled, new Random(n));
            IndexBuilder parallel = new IndexBuilder(new Tokenizer(), 1); parallel.buildParallel(shuffled, n);
            assertEquivalent(docs, baseline.getIndex(), parallel.getIndex());
            assertEquals(n, parallel.getThreadCount());
            assertEquals(parallel.getLastBuildTime() / 1_000_000.0, parallel.getLastBuildTimeMillis());
        }
    }
    @Test void concurrentWritersAndReadersDoNotLosePostings() throws Exception {
        InvertedIndex index = new InvertedIndex(1);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < 8; w++) {
                int worker = w;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 250; i++) {
                        String id = worker + ":" + i;
                        index.addTerm("shared", id); index.addTerm("shared", id);
                        index.addTerm("collision", id);
                        assertTrue(index.search("shared").contains(id));
                        index.getDocumentCount();
                    }
                    return null;
                }));
            }
            start.countDown(); for (Future<?> f : futures) f.get();
        }
        assertEquals(2000, index.getDocumentCount());
        assertEquals(2000, index.search("shared").size());
        assertEquals(index.search("shared"), index.search("collision"));
    }
    @Test void readersSeeOldIndexUntilSuccessfulPublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        IndexBuilder builder = new IndexBuilder(text -> {
            if (text.equals("new")) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }
            return List.of(text);
        }, 4);
        builder.build(List.of(new Document("old", "old", "old")));
        var old = builder.getSnapshot();
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            Future<?> build = caller.submit(() -> builder.buildParallel(List.of(new Document("new", "new", "new")), 2));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertSame(old, builder.getSnapshot());
                assertEquals(Set.of("old"), builder.getIndex().search("old"));
            } finally { release.countDown(); }
            build.get();
        }
        assertNotSame(old, builder.getSnapshot());
        assertTrue(builder.getIndex().search("old").isEmpty());
        assertEquals(Set.of("new"), builder.getIndex().search("new"));
        builder.buildParallel(List.of(), 8); assertEquals(0, builder.getIndex().getDocumentCount());
    }
    @Test void workerFailurePreservesSnapshotAndTerminatesWorkers() {
        AtomicInteger active = new AtomicInteger();
        IndexBuilder builder = new IndexBuilder(text -> {
            active.incrementAndGet();
            try { if (text.equals("fail")) throw new IllegalStateException("intentional"); return List.of(text); }
            finally { active.decrementAndGet(); }
        }, 4);
        builder.build(List.of(new Document("old", "old", "old"))); var old = builder.getSnapshot();
        IndexBuildException error = assertThrows(IndexBuildException.class, () -> builder.buildParallel(
                List.of(new Document("a", "fail", "a"), new Document("b", "ok", "b")), 2));
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertSame(old, builder.getSnapshot()); assertEquals(0, active.get());
    }
    @Test void invalidInputPreservesSnapshot() {
        IndexBuilder b = new IndexBuilder(); b.build(corpus(5)); var old = b.getSnapshot();
        assertThrows(IllegalArgumentException.class, () -> b.buildParallel(corpus(1), 0));
        assertThrows(IllegalArgumentException.class, () -> b.buildParallel(corpus(1), -1));
        Document d = corpus(1).getFirst();
        assertThrows(IllegalArgumentException.class, () -> b.buildParallel(List.of(d, d), 4));
        assertThrows(NullPointerException.class, () -> b.buildParallel(Arrays.asList(d, null), 4));
        assertSame(old, b.getSnapshot());
    }
    @Test void interruptionCancelsWorkersAndRestoresInterruptFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean stopped = new AtomicBoolean(), interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        IndexBuilder b = new IndexBuilder(text -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return List.of(text); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            finally { stopped.set(true); }
        }, 4);
        var old = b.getSnapshot();
        Thread caller = new Thread(() -> {
            try { b.buildParallel(corpus(1), 2); }
            catch (Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start();
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
        finally { caller.interrupt(); caller.join(5000); }
        assertFalse(caller.isAlive()); assertTrue(stopped.get()); assertTrue(interrupted.get());
        assertInstanceOf(IndexBuildException.class, failure.get()); assertSame(old, b.getSnapshot());
    }
    @Test void simultaneousRebuildsPublishWholeSnapshots() throws Exception {
        IndexBuilder b = new IndexBuilder();
        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            Future<?> first = callers.submit(() -> b.buildParallel(corpus(100), 2));
            Future<?> second = callers.submit(() -> b.buildParallel(corpus(200), 4));
            first.get(); second.get();
        }
        var result = b.getSnapshot();
        assertTrue(result.index().getDocumentCount() == 100 || result.index().getDocumentCount() == 200);
        assertEquals(result.index().getDocumentCount() == 100 ? 2 : 4, result.threads());
    }
}
