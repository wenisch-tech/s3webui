package tech.wenisch.s3webui.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.s3webui.model.AuditEvent;
import tech.wenisch.s3webui.service.AuditHistoryService;

import java.util.List;

@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class AuditController {

    private final AuditHistoryService auditHistoryService;

    @GetMapping
    public ResponseEntity<List<AuditEvent>> listHistory() {
        return ResponseEntity.ok(auditHistoryService.list());
    }
}