package com.coursework.backend.dto;

public record StatsResponse(String status, int documents, int unique_terms, double index_build_time_ms, int threads_used) {}
