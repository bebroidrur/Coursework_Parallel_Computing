package com.coursework.backend.search;

import com.coursework.backend.dto.SearchResponse;
import java.util.List;

/** Receives progress from the same search algorithm used by REST. */
public interface SearchProgressListener {
    void queryExpansion(List<String> terms, boolean fallback, String warning);
    void keywordSearch(int resultCount);
    void done(SearchResponse response);
    void error(Throwable error);

    SearchProgressListener NONE = new SearchProgressListener() {
        public void queryExpansion(List<String> terms, boolean fallback, String warning) { }
        public void keywordSearch(int resultCount) { }
        public void done(SearchResponse response) { }
        public void error(Throwable error) { }
    };
}
