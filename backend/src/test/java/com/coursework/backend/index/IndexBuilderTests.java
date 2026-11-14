package com.coursework.backend.index;
import com.coursework.backend.document.Document;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class IndexBuilderTests {
    private Document doc(String id, String text) { return new Document(id, text, id); }
    @Test void buildsSequentiallyAndCountsEmptyDocuments() {
        IndexBuilder builder = new IndexBuilder();
        builder.build(List.of(doc("1", "Java JAVA пошук"), doc("2", "Пошук"), doc("3", "")));
        assertEquals(Set.of("1", "2"), builder.getIndex().search("пошук"));
        assertEquals(Set.of("1"), builder.getIndex().search("java"));
        assertEquals(3, builder.getIndex().getDocumentCount());
        assertEquals(1, builder.getThreadCount()); assertTrue(builder.getLastBuildTime() >= 0);
    }
    @Test void rebuildReplacesPreviousContents() {
        IndexBuilder builder = new IndexBuilder(); builder.build(List.of(doc("1", "old")));
        builder.build(List.of(doc("2", "new")));
        assertTrue(builder.getIndex().search("old").isEmpty());
        assertEquals(Set.of("2"), builder.getIndex().search("new"));
        builder.build(List.of()); assertEquals(0, builder.getIndex().getDocumentCount());
    }
    @Test void duplicateIdsFailWithoutReplacingPreviousIndex() {
        IndexBuilder builder = new IndexBuilder(); builder.build(List.of(doc("old", "stable")));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(doc("1", "a"), doc("1", "b"))));
        assertEquals(Set.of("old"), builder.getIndex().search("stable"));
    }
}
