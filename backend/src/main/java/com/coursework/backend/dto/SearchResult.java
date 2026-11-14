package com.coursework.backend.dto;

public record SearchResult(String document_id, int score, String snippet) {}
