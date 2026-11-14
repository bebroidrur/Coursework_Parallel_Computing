package com.coursework.backend.controller;
import com.coursework.backend.dto.*;
import com.coursework.backend.search.SearchService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api")
public class StatsController {
    private final SearchService service;
    public StatsController(SearchService service) { this.service = service; }
    @GetMapping("/stats") public StatsResponse stats() { return service.stats(); }
}
