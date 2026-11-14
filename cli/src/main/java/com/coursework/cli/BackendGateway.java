package com.coursework.cli;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.function.Consumer;

/** Transport boundary so command handling can be tested without a running backend. */
public interface BackendGateway {
    JsonNode health() throws IOException, InterruptedException;
    JsonNode index(int threads) throws IOException, InterruptedException;
    JsonNode search(String query, String mode) throws IOException, InterruptedException;
    JsonNode stats() throws IOException, InterruptedException;
    void searchLive(String requestId, String query, String mode, Consumer<JsonNode> eventConsumer)
            throws IOException, InterruptedException;
}
