package com.coursework.backend.document;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DatasetPathResolverTests {
    @Test void existingAbsolutePathIsPreserved() throws Exception {
        Path directory = Files.createTempDirectory("dataset-resolver-");
        assertEquals(directory.toAbsolutePath().normalize(), DatasetPathResolver.resolve(directory.toString()));
    }

    @Test void repositoryDatasetResolvesWhenWorkingDirectoryIsBackend() {
        Path resolved = DatasetPathResolver.resolve("datasets/sample");
        assertTrue(Files.isDirectory(resolved), "resolved dataset: " + resolved);
        assertTrue(Files.exists(resolved.resolve("01-parallel.txt")));
    }
}
