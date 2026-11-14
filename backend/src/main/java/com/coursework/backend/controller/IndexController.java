package com.coursework.backend.controller;
import com.coursework.backend.dto.*;
import com.coursework.backend.search.SearchService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api")
public class IndexController {
    private final SearchService service;
    public IndexController(SearchService service) { this.service = service; }
    @PostMapping("/index") public StatsResponse index(@RequestBody IndexRequest request) { return service.index(request); }
}
