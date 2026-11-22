package com.coursework.backend.document;
import com.coursework.backend.index.IndexBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class DatasetLoaderTests {
    @TempDir Path directory;
    @Test void loadsUtf8RecursivelyInStableOrderAndFiltersExtensions() throws IOException {
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("nested/b.txt"), "Пошук пам’ять", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("a.txt"), "", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("generation-manifest.txt"), "documents=999", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("ignored.md"), "ignored", StandardCharsets.UTF_8);
        var docs = new DatasetLoader().load(directory);
        assertEquals(List.of("a.txt", "nested/b.txt"), docs.stream().map(Document::getId).toList());
        assertEquals("Пошук пам’ять", docs.get(1).getContent());
        IndexBuilder builder = new IndexBuilder(); builder.build(docs);
        assertEquals(2, builder.getIndex().getDocumentCount());
        assertEquals(Set.of("nested/b.txt"), builder.getIndex().search("пошук"));
    }
    @Test void handlesEmptyDirectory() throws IOException { assertTrue(new DatasetLoader().load(directory).isEmpty()); }
    @Test void loadsLightweightReferencesInStableOrder() throws IOException {
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("nested/b.txt"), "reference content", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("a.txt"), "another reference", StandardCharsets.UTF_8);
        var refs = new DatasetLoader().loadRefs(directory);
        assertEquals(List.of("a.txt", "nested/b.txt"), refs.stream().map(DocumentRef::getId).toList());
        assertEquals(directory.resolve("a.txt").toAbsolutePath().normalize(), refs.getFirst().getPath());
    }
    @Test void referenceIndexingReadsOneFilePerWorkerAndSearchesIt() throws IOException {
        Files.writeString(directory.resolve("one.txt"), "Parallel Java", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("two.txt"), "Sequential search", StandardCharsets.UTF_8);
        var builder = new IndexBuilder();
        builder.buildParallelRefs(new DatasetLoader().loadRefs(directory), 2);
        assertEquals(2, builder.getIndex().getDocumentCount());
        assertEquals(Set.of("one.txt"), builder.getIndex().search("parallel"));
        assertEquals(Set.of("two.txt"), builder.getIndex().search("search"));
    }
    @Test void rejectsMissingDirectory() { assertThrows(IOException.class, () -> new DatasetLoader().load(directory.resolve("missing"))); }
    @Test void rejectsMalformedUtf8() throws IOException {
        Files.write(directory.resolve("bad.txt"), new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThrows(IOException.class, () -> new DatasetLoader().load(directory));
    }
}
