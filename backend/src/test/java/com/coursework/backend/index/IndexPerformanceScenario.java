package com.coursework.backend.index;

import com.coursework.backend.document.Document;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;

/** Explicit opt-in scenario: -Dtest=IndexPerformanceScenario; no speedup assertions. */
class IndexPerformanceScenario {
    @Test void measure() throws Exception {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            StringBuilder content = new StringBuilder();
            for (int t = 0; t < 150; t++) content.append("term").append((i * 17 + t) % 1000).append(' ');
            docs.add(new Document("doc" + i, content.toString(), "doc" + i));
        }
        IndexBuilder reference = new IndexBuilder(); reference.build(docs);
        StringBuilder csv = new StringBuilder("mode,threads,iteration,milliseconds\n");
        int[] modes = {0, 1, 2, 4, 8};
        for (int round = -2; round < 5; round++) {
            // Rotate order to reduce systematic order bias; first two rounds warm up each mode.
            for (int j = 0; j < modes.length; j++) {
                int n = modes[Math.floorMod(j + round, modes.length)];
                IndexBuilder b = new IndexBuilder();
                if (n == 0) b.build(docs); else b.buildParallel(docs, n);
                ParallelIndexTests.assertEquivalent(docs, reference.getIndex(), b.getIndex());
                if (round >= 0) csv.append(String.format(Locale.ROOT, "%s,%d,%d,%.3f%n",
                        n == 0 ? "sequential" : "pool", Math.max(1, n), round + 1, b.getLastBuildTimeMillis()));
            }
        }
        Path output = Path.of("target", "benchmarks"); Files.createDirectories(output);
        Files.writeString(output.resolve("index-times.csv"), csv);
        String environment = "java=" + System.getProperty("java.version") + "\nos=" + System.getProperty("os.name")
                + " " + System.getProperty("os.version") + "\narch=" + System.getProperty("os.arch")
                + "\nprocessors=" + Runtime.getRuntime().availableProcessors()
                + "\nmaxHeapBytes=" + Runtime.getRuntime().maxMemory()
                + "\ndocuments=2000\ntokensPerDocument=150\nvocabulary=1000\nbuckets=1024\nwarmups=2\nrepeats=5\n";
        Files.writeString(output.resolve("environment.txt"), environment);
        System.out.println(environment + csv);
    }
}
