package com.coursework.backend.index;

import java.util.Objects;

public final class TermEntry {
    private final String term;
    private final PostingList postings = new PostingList();

    public TermEntry(String term) {
        if (Objects.requireNonNull(term, "term").isBlank()) throw new IllegalArgumentException("Blank term");
        this.term = term;
    }
    public String getTerm() { return term; }
    public PostingList getPostings() { return postings; }
}
