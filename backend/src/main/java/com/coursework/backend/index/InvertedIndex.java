package com.coursework.backend.index;

import java.util.Objects;
import java.util.Set;

/** Concurrent bucket-based index; search accepts an already normalized single term. */
public final class InvertedIndex {
    private final Bucket[] buckets;
    private final PostingList documents = new PostingList();

    public InvertedIndex() { this(1024); }
    public InvertedIndex(int bucketCount) {
        if (bucketCount < 1) throw new IllegalArgumentException("bucketCount must be positive");
        buckets = new Bucket[bucketCount];
        for (int i = 0; i < bucketCount; i++) buckets[i] = new Bucket();
    }
    public void registerDocument(String docId) { synchronized (documents) { documents.add(docId); } }
    public void addTerm(String term, String docId) {
        if (Objects.requireNonNull(term, "term").isBlank()) throw new IllegalArgumentException("Blank term");
        registerDocument(docId);
        addRegisteredTerm(term, docId);
    }
    /** Builder-only fast path: all document ids are registered before workers start. */
    void addRegisteredTerm(String term, String docId) {
        if (Objects.requireNonNull(term, "term").isBlank()) throw new IllegalArgumentException("Blank term");
        buckets[bucketPosition(term)].insert(term, docId);
    }
    public Set<String> search(String term) {
        Objects.requireNonNull(term, "term");
        return buckets[bucketPosition(term)].find(term);
    }
    /** Exact for completed indexes; concurrent mutations may span multiple bucket snapshots. */
    public int getUniqueTermCount() {
        int count = 0;
        for (Bucket bucket : buckets) count += bucket.getTermCount();
        return count;
    }
    private int bucketPosition(String term) { return Math.floorMod(term.hashCode(), buckets.length); }
    public int getDocumentCount() { synchronized (documents) { return documents.size(); } }
}
