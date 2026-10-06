package tech.wenisch.s3webui.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.s3webui.service.GlobalSearchService;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class GlobalSearchApiController {

    private final GlobalSearchService globalSearchService;

    @GetMapping
    public ResponseEntity<GlobalSearchService.SearchPage> search(
            @RequestParam(name = "q") String query,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(defaultValue = "false") boolean refresh) {
        if (limit < 1 || limit > GlobalSearchService.MAX_RESULT_LIMIT) {
            throw new IllegalArgumentException(
                    "Search result limit must be between 1 and " + GlobalSearchService.MAX_RESULT_LIMIT);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(globalSearchService.search(query, 0, limit, refresh));
    }
}
