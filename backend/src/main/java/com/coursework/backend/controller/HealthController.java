package com.coursework.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.coursework.backend.dto.HealthResponse;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final com.coursework.backend.ollama.OllamaService ollama;
    public HealthController(com.coursework.backend.ollama.OllamaService ollama) { this.ollama = ollama; }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok", ollama.isAvailable());
    }
}
