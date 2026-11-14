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
        Files.writeString(directory.resolve("ignored.md"), "ignored", StandardCharsets.UTF_8);
        var docs = new DatasetLoader().load(directory);
        assertEquals(List.of("a.txt", "nested/b.txt"), docs.stream().map(Document::getId).toList());
        assertEquals("Пошук пам’ять", docs.get(1).getContent());
        IndexBuilder builder = new IndexBuilder(); builder.build(docs);
        assertEquals(2, builder.getIndex().getDocumentCount());
        assertEquals(Set.of("nested/b.txt"), builder.getIndex().search("пошук"));
    }
    @Test void handlesEmptyDirectory() throws IOException { assertTrue(new DatasetLoader().load(directory).isEmpty()); }
    @Test void rejectsMissingDirectory() { assertThrows(IOException.class, () -> new DatasetLoader().load(directory.resolve("missing"))); }
    @Test void rejectsMalformedUtf8() throws IOException {
        Files.write(directory.resolve("bad.txt"), new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThrows(IOException.class, () -> new DatasetLoader().load(directory));
    }
}
