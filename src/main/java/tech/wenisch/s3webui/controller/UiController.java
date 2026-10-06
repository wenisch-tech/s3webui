package tech.wenisch.s3webui.controller;

import tech.wenisch.s3webui.service.S3Service;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;
import tech.wenisch.s3webui.service.GlobalSearchService;
import tech.wenisch.s3webui.config.AuthenticationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Slf4j
@Controller
@RequiredArgsConstructor
public class UiController {

    private final S3Service s3Service;
    private final S3ConnectionSettingsService s3ConnectionSettingsService;
    private final AuthenticationProperties authenticationProperties;
    private final GlobalSearchService globalSearchService;

    @GetMapping("/")
    public String buckets(Model model) {
        if (s3ConnectionSettingsService.isSelectionRequired()) {
            model.addAttribute("buckets", java.util.List.of());
            model.addAttribute("error", null);
            return "buckets";
        }

        try {
            model.addAttribute("buckets", s3Service.listBuckets());
            model.addAttribute("error", null);
        } catch (Exception e) {
            log.error("Failed to list buckets", e);
            model.addAttribute("buckets", java.util.List.of());
            model.addAttribute("error", e.getMessage());
        }
        return "buckets";
    }

    @GetMapping("/buckets/{bucket}")
    public String bucket(@PathVariable String bucket,
                         @RequestParam(required = false, defaultValue = "") String prefix,
                         @RequestParam(required = false, defaultValue = "") String highlight,
                         Model model) {
        if (s3ConnectionSettingsService.isSelectionRequired()) {
            model.addAttribute("bucket", bucket);
            model.addAttribute("prefix", prefix);
            model.addAttribute("objects", java.util.List.of());
            model.addAttribute("breadcrumbs", buildBreadcrumbs(prefix));
            model.addAttribute("highlight", highlight);
            model.addAttribute("error", null);
            return "bucket";
        }

        model.addAttribute("bucket", bucket);
        model.addAttribute("prefix", prefix);
        model.addAttribute("highlight", highlight);
        try {
            model.addAttribute("objects", s3Service.listObjects(bucket, prefix));
            model.addAttribute("breadcrumbs", buildBreadcrumbs(prefix));
            model.addAttribute("error", null);
        } catch (Exception e) {
            log.error("Failed to list objects in bucket {}", bucket, e);
            model.addAttribute("objects", java.util.List.of());
            model.addAttribute("breadcrumbs", java.util.List.of());
            model.addAttribute("error", e.getMessage());
        }
        return "bucket";
    }

    @GetMapping("/search")
    public String search(
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "false") boolean refresh,
            Model model) {
        final int pageSize = 50;
        int requestedPage = Math.max(1, page);
        model.addAttribute("query", query == null ? "" : query.trim());
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("error", null);

        try {
            String normalizedQuery = GlobalSearchService.normalizeQuery(query);
            int offset = Math.toIntExact(Math.min(
                    (long) (requestedPage - 1) * pageSize,
                    Integer.MAX_VALUE));
            var results = globalSearchService.search(normalizedQuery, offset, pageSize, refresh);
            int pageCount = Math.max(1, (results.total() + pageSize - 1) / pageSize);
            int currentPage = Math.min(requestedPage, pageCount);
            if (currentPage != requestedPage) {
                results = globalSearchService.search(normalizedQuery, (currentPage - 1) * pageSize, pageSize, false);
            }
            model.addAttribute("query", normalizedQuery);
            model.addAttribute("searchResults", results);
            model.addAttribute("currentPage", currentPage);
            model.addAttribute("pageCount", pageCount);
        } catch (Exception exception) {
            log.error("Global search failed", exception);
            model.addAttribute("searchResults", null);
            model.addAttribute("currentPage", 1);
            model.addAttribute("pageCount", 1);
            model.addAttribute("error", exception.getMessage());
        }
        return "search";
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        @RequestParam(required = false) String oidcError,
                        Model model) {
        if (authenticationProperties.isDisabled()) {
            return "redirect:/";
        }
        model.addAttribute("loginError", error != null);
        model.addAttribute("loggedOut", logout != null);
        model.addAttribute("oidcError", oidcError != null);
        return "login";
    }

    @GetMapping("/access-denied")
    public String accessDenied() {
        return "access-denied";
    }

    @GetMapping("/history")
    public String history() {
        return "history";
    }

    private java.util.List<java.util.Map<String, String>> buildBreadcrumbs(String prefix) {
        var crumbs = new java.util.ArrayList<java.util.Map<String, String>>();
        if (prefix == null || prefix.isBlank()) {
            return crumbs;
        }
        String[] parts = prefix.split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            current.append(part).append("/");
            crumbs.add(java.util.Map.of("name", part, "prefix", current.toString()));
        }
        return crumbs;
    }
}
