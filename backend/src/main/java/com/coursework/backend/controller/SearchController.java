package com.coursework.backend.controller;
import com.coursework.backend.dto.*;
import com.coursework.backend.search.SearchService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api")
public class SearchController {
    private final SearchService service;
    public SearchController(SearchService service) { this.service = service; }
    @PostMapping("/search") public SearchResponse search(@RequestBody SearchRequest request) { return service.search(request); }
}
