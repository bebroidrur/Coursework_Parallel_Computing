package com.coursework.backend.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Sorted unique document ids. Binary lookup O(log n), insertion O(n). */
public final class PostingList {
    private final List<String> documentIds = new ArrayList<>();

    public synchronized void add(String documentId) {
        if (Objects.requireNonNull(documentId, "documentId").isBlank()) throw new IllegalArgumentException("Blank document id");
        int position = Collections.binarySearch(documentIds, documentId);
        if (position < 0) documentIds.add(-position - 1, documentId);
    }
    public synchronized List<String> getDocumentIds() { return List.copyOf(documentIds); }
    public synchronized int size() { return documentIds.size(); }
}
