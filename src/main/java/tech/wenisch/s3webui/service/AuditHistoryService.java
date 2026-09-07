package tech.wenisch.s3webui.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.s3webui.entity.AuditLogEntry;
import tech.wenisch.s3webui.model.AuditEvent;
import tech.wenisch.s3webui.repository.AuditLogEntryRepository;

import java.time.Instant;
import java.util.List;

/**
 * Records what users do with their S3 session, persisted so history survives a restart.
 *
 * <p>Because every user browses with their own key, history is scoped: administrators see every
 * entry, everyone else sees only their own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditHistoryService {

    private static final int MAX_EVENTS = 1000;
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final AuditLogEntryRepository auditLogEntryRepository;
    private final S3ConnectionSettingsService s3ConnectionSettingsService;

    @Transactional
    public void record(String user, String action, String resourceType, String bucket, String key, String details) {
        auditLogEntryRepository.save(AuditLogEntry.builder()
                .timestamp(Instant.now())
                .userEmail(resolveActor(user))
                .credentialName(s3ConnectionSettingsService.getActiveCredentialName())
                .action(action)
                .resourceType(resourceType)
                .bucket(bucket)
                .objectKey(key)
                .details(details)
                .build());

        trimHistory();
    }

    /** History visible to the current user, newest first. */
    @Transactional(readOnly = true)
    public List<AuditEvent> list() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        PageRequest page = PageRequest.of(0, MAX_EVENTS);

        List<AuditLogEntry> entries = isAdmin(authentication)
                ? auditLogEntryRepository.findAllByOrderByIdDesc(page)
                : auditLogEntryRepository.findByUserEmailIgnoreCaseOrderByIdDesc(currentActor(authentication), page);

        return entries.stream().map(AuditHistoryService::toEvent).toList();
    }

    private void trimHistory() {
        List<Long> newest = auditLogEntryRepository.findIdsNewestFirst(PageRequest.of(0, MAX_EVENTS));
        if (newest.size() == MAX_EVENTS) {
            auditLogEntryRepository.deleteByIdLessThan(newest.get(newest.size() - 1));
        }
    }

    private static AuditEvent toEvent(AuditLogEntry entry) {
        return AuditEvent.builder()
                .timestamp(entry.getTimestamp())
                .user(entry.getUserEmail())
                .credential(entry.getCredentialName())
                .action(entry.getAction())
                .resourceType(entry.getResourceType())
                .bucket(entry.getBucket())
                .key(entry.getObjectKey())
                .details(entry.getDetails())
                .build();
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }

    private static String currentActor(Authentication authentication) {
        String email = AuthenticationIdentity.emailOf(authentication);
        if (email != null) {
            return email;
        }
        return authentication == null ? "anonymous" : authentication.getName();
    }

    private String resolveActor(String user) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = AuthenticationIdentity.emailOf(authentication);
        if (email != null) {
            return email;
        }
        if (user == null || user.isBlank() || "anonymousUser".equalsIgnoreCase(user)) {
            return "anonymous";
        }
        return user;
    }
}
