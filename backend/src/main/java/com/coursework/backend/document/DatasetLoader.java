package com.coursework.backend.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DatasetLoader {
    /** Recursively loads .txt files in stable relative-path order; ignores symbolic links. */
    public List<Document> load(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Dataset directory does not exist: " + root);
        }
        List<Document> documents = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted().toList()) {
                String id = root.relativize(path).toString().replace('\\', '/');
                documents.add(new Document(id, Files.readString(path, StandardCharsets.UTF_8), path.toString()));
            }
        }
        return List.copyOf(documents);
    }
}
