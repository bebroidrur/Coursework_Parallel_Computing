package com.coursework.backend.dto;

public record SearchResponse(String status, String query, String mode, java.util.List<String> terms, int total, java.util.List<SearchResult> results, java.util.List<String> stages, boolean fallback, String warning) {}
