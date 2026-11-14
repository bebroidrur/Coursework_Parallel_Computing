package com.coursework.backend.index;

import com.coursework.backend.document.Document;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Explicit benchmark: run with -Dtest=ExtendedPerformanceScenario.
 * The test records every measurement and checks index equivalence on every measured run.
 */
class ExtendedPerformanceScenario {
    private static final int[] SIZES = {500, 2_000, 5_000, 10_000};
    private static final int[] THREADS = {1, 2, 4, 8};
    private static final int WARMUPS = 2;
    private static final int REPEATS = 5;
    private static final int TOKENS_PER_DOCUMENT = 150;
    private static final int VOCABULARY = 1_000;

    @Test
    void measureAllSizes() throws Exception {
        StringBuilder raw = new StringBuilder("documents,mode,threads,iteration,milliseconds\n");
        List<Measurement> measurements = new ArrayList<>();

        for (int size : SIZES) {
            List<Document> documents = corpus(size);
            IndexBuilder reference = new IndexBuilder();
            reference.build(documents);

            for (int i = 0; i < WARMUPS; i++) buildAndCheck(documents, reference, 0);
            for (int i = 1; i <= REPEATS; i++) {
                double millis = buildAndCheck(documents, reference, 0);
                measurements.add(new Measurement(size, "sequential", 1, millis));
                raw.append(row(size, "sequential", 1, i, millis));
            }

            for (int threads : THREADS) {
                for (int i = 0; i < WARMUPS; i++) buildAndCheck(documents, reference, threads);
                for (int i = 1; i <= REPEATS; i++) {
                    double millis = buildAndCheck(documents, reference, threads);
                    measurements.add(new Measurement(size, "pool", threads, millis));
                    raw.append(row(size, "pool", threads, i, millis));
                }
            }
        }

        StringBuilder summary = new StringBuilder(
                "documents,mode,threads,mean_ms,median_ms,speedup,efficiency\n");
        for (int size : SIZES) {
            double sequentialMean = mean(measurements, size, "sequential", 1);
            summary.append(summaryRow(size, "sequential", 1, sequentialMean, sequentialMean, 1.0, 1.0));
            for (int threads : THREADS) {
                List<Double> values = values(measurements, size, "pool", threads);
                double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                double median = median(values);
                double speedup = sequentialMean / mean;
                summary.append(summaryRow(size, "pool", threads, mean, median, speedup, speedup / threads));
            }
        }

        Path output = Path.of("target", "benchmarks");
        Files.createDirectories(output);
        Files.writeString(output.resolve("extended-index-times.csv"), raw);
        Files.writeString(output.resolve("extended-index-summary.csv"), summary);
        String environment = "java=" + System.getProperty("java.version") + "\n"
                + "os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n"
                + "arch=" + System.getProperty("os.arch") + "\n"
                + "processors=" + Runtime.getRuntime().availableProcessors() + "\n"
                + "maxHeapBytes=" + Runtime.getRuntime().maxMemory() + "\n"
                + "sizes=500,2000,5000,10000\n"
                + "tokensPerDocument=" + TOKENS_PER_DOCUMENT + "\n"
                + "vocabulary=" + VOCABULARY + "\n"
                + "buckets=1024\n"
                + "warmups=" + WARMUPS + "\n"
                + "repeats=" + REPEATS + "\n"
                + "executor=CustomThreadPool\n";
        Files.writeString(output.resolve("extended-environment.txt"), environment);
        System.out.println(environment + summary);
    }

    private static List<Document> corpus(int count) {
        List<Document> documents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder content = new StringBuilder(TOKENS_PER_DOCUMENT * 8);
            for (int token = 0; token < TOKENS_PER_DOCUMENT; token++) {
                content.append("term").append((i * 17 + token) % VOCABULARY).append(' ');
            }
            documents.add(new Document("doc" + i, content.toString(), "doc" + i));
        }
        return documents;
    }

    private static double buildAndCheck(List<Document> documents, IndexBuilder reference, int threads) {
        IndexBuilder builder = new IndexBuilder();
        if (threads == 0) builder.build(documents);
        else builder.buildParallel(documents, threads);
        ParallelIndexTests.assertEquivalent(documents, reference.getIndex(), builder.getIndex());
        return builder.getLastBuildTimeMillis();
    }

    private static String row(int size, String mode, int threads, int iteration, double millis) {
        return String.format(Locale.ROOT, "%d,%s,%d,%d,%.3f%n", size, mode, threads, iteration, millis);
    }

    private static String summaryRow(int size, String mode, int threads, double mean, double median,
                                     double speedup, double efficiency) {
        return String.format(Locale.ROOT, "%d,%s,%d,%.3f,%.3f,%.6f,%.6f%n",
                size, mode, threads, mean, median, speedup, efficiency);
    }

    private static List<Double> values(List<Measurement> all, int size, String mode, int threads) {
        return all.stream().filter(m -> m.documents == size && m.mode.equals(mode) && m.threads == threads)
                .map(m -> m.milliseconds).toList();
    }

    private static double mean(List<Measurement> all, int size, String mode, int threads) {
        return values(all, size, mode, threads).stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0 : sorted.get(middle);
    }

    private record Measurement(int documents, String mode, int threads, double milliseconds) { }
}
