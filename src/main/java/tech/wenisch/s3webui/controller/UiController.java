package tech.wenisch.s3webui.controller;

import tech.wenisch.s3webui.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${oidc.enabled:false}")
    private boolean oidcEnabled;

    @GetMapping("/")
    public String buckets(Model model) {
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
                         Model model) {
        try {
            model.addAttribute("bucket", bucket);
            model.addAttribute("prefix", prefix);
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

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        Model model) {
        if (!oidcEnabled) {
            return "redirect:/";
        }
        model.addAttribute("loginError", error != null);
        model.addAttribute("loggedOut", logout != null);
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
