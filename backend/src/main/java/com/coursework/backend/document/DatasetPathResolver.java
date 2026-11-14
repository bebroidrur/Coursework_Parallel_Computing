package com.coursework.backend.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves the default repository dataset independently of the process working directory. */
public final class DatasetPathResolver {
    private DatasetPathResolver() {}

    public static Path resolve(String configured) {
        Path value = Path.of(configured).normalize();
        if (value.isAbsolute() || Files.isDirectory(value)) return value;

        // This covers running the executable JAR from backend/: the repository
        // dataset is one directory above the current working directory.
        for (Path current = Path.of("").toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            Path candidate = current.resolve(value).normalize();
            if (Files.isDirectory(candidate)) return candidate;
        }

        Path projectRoot = codeSourceProjectRoot();
        if (projectRoot != null) {
            Path fromProject = projectRoot.resolve(value).normalize();
            if (Files.isDirectory(fromProject)) return fromProject;
        }
        return value;
    }

    private static Path codeSourceProjectRoot() {
        try {
            Path location = Path.of(DatasetPathResolver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            // target/classes -> backend/target; executable jar -> backend/target.
            Path target = Files.isDirectory(location) && "classes".equals(location.getFileName().toString()) ? location.getParent() : location.getParent();
            if (target != null && "target".equals(target.getFileName().toString())) {
                Path backend = target.getParent();
                return backend == null ? null : backend.getParent();
            }
        } catch (Exception ignored) { }
        return null;
    }
}
