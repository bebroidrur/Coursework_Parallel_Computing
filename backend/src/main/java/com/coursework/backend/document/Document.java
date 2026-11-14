package com.coursework.backend.document;

import java.util.Objects;

/** Immutable UTF-8 source document; id is a dataset-relative path. */
public final class Document {
    private final String id;
    private final String content;
    private final String path;

    public Document(String id, String content, String path) {
        if (Objects.requireNonNull(id, "id").isBlank()) throw new IllegalArgumentException("Blank document id");
        this.id = id;
        this.content = Objects.requireNonNull(content, "content");
        this.path = Objects.requireNonNull(path, "path");
    }
    public String getId() { return id; }
    public String getContent() { return content; }
    public String getPath() { return path; }
}
