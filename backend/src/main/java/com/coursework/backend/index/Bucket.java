package com.coursework.backend.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Separate chaining with a list, not a map. */
public final class Bucket {
    private final List<TermEntry> entries = new ArrayList<>();

    public synchronized void insert(String term, String docId) {
        for (TermEntry entry : entries) {
            if (entry.getTerm().equals(term)) {
                entry.getPostings().add(docId);
                return;
            }
        }
        TermEntry entry = new TermEntry(term);
        entry.getPostings().add(docId);
        entries.add(entry);
    }
    public synchronized int getTermCount() { return entries.size(); }

    public synchronized Set<String> find(String term) {
        for (TermEntry entry : entries) {
            if (entry.getTerm().equals(term)) {
                return Collections.unmodifiableSet(new TreeSet<>(entry.getPostings().getDocumentIds()));
            }
        }
        return Set.of();
    }
}
