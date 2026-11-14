package com.coursework.backend.index;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class InvertedIndexTests {
    @Test void indexesTermsAcrossDocumentsWithoutDuplicatePostings() {
        InvertedIndex index = new InvertedIndex();
        index.addTerm("java", "b"); index.addTerm("java", "a"); index.addTerm("java", "a");
        index.addTerm("search", "a");
        assertEquals(Set.of("a", "b"), index.search("java"));
        assertEquals(Set.of("a"), index.search("search"));
        assertEquals(2, index.getDocumentCount());
    }
    @Test void resolvesCollisionsInSingleBucket() {
        InvertedIndex index = new InvertedIndex(1);
        index.addTerm("alpha", "1"); index.addTerm("beta", "2"); index.addTerm("alpha", "3");
        assertEquals(Set.of("1", "3"), index.search("alpha"));
        assertEquals(Set.of("2"), index.search("beta"));
        assertTrue(index.search("missing").isEmpty());
    }
    @Test void resolvesIdenticalStringHashes() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        InvertedIndex index = new InvertedIndex();
        index.addTerm("Aa", "1"); index.addTerm("BB", "2");
        assertEquals(Set.of("1"), index.search("Aa"));
        assertEquals(Set.of("2"), index.search("BB"));
    }
    @Test void handlesNegativeHashes() {
        String term = "polygenelubricants";
        assertEquals(Integer.MIN_VALUE, term.hashCode());
        InvertedIndex index = new InvertedIndex(7);
        index.addTerm(term, "1"); assertEquals(Set.of("1"), index.search(term));
    }
    @Test void missingTermAndEmptyIndexReturnEmptySet() {
        assertTrue(new InvertedIndex().search("unknown").isEmpty());
    }
    @Test void resultsCannotMutateIndexAndAreSnapshots() {
        InvertedIndex index = new InvertedIndex(); index.addTerm("java", "1");
        Set<String> result = index.search("java");
        assertThrows(UnsupportedOperationException.class, () -> result.add("2"));
        index.addTerm("java", "3"); assertEquals(Set.of("1"), result);
    }
    @Test void validatesInput() {
        assertThrows(IllegalArgumentException.class, () -> new InvertedIndex(0));
        InvertedIndex index = new InvertedIndex();
        assertThrows(IllegalArgumentException.class, () -> index.addTerm(" ", "1"));
        assertThrows(IllegalArgumentException.class, () -> index.addTerm("java", ""));
        assertEquals(0, index.getDocumentCount());
    }
    @Test void postingListIsSortedUniqueAndImmutable() {
        PostingList list = new PostingList(); list.add("c"); list.add("a"); list.add("c"); list.add("b");
        assertEquals(List.of("a", "b", "c"), list.getDocumentIds());
        assertThrows(UnsupportedOperationException.class, () -> list.getDocumentIds().clear());
    }
}
