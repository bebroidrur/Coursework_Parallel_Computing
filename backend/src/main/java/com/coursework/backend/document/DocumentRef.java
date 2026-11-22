package com.coursework.backend.document;

import java.nio.file.Path;
import java.util.Objects;

/** Lightweight dataset entry used by the bounded-memory indexing path. */
public final class DocumentRef {
    private final String id;
    private final Path path;

    public DocumentRef(String id, Path path) {
        if (Objects.requireNonNull(id, "id").isBlank()) throw new IllegalArgumentException("Blank document id");
        this.id = id;
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public String getId() { return id; }
    public Path getPath() { return path; }
}
