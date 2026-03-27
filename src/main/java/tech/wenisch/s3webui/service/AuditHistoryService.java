package tech.wenisch.s3webui.service;

import tech.wenisch.s3webui.model.AuditEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class AuditHistoryService {

    private static final int MAX_EVENTS = 1000;

    private final ConcurrentLinkedDeque<AuditEvent> events = new ConcurrentLinkedDeque<>();

    @Value("${oidc.enabled:false}")
    private boolean oidcEnabled;

    public void record(String user, String action, String resourceType, String bucket, String key, String details) {
        String actor = resolveActor(user);
        events.addFirst(AuditEvent.builder()
                .timestamp(Instant.now())
                .user(actor)
                .action(action)
                .resourceType(resourceType)
                .bucket(bucket)
                .key(key)
                .details(details)
                .build());

        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
    }

    public List<AuditEvent> list() {
        return new ArrayList<>(events);
    }

    private String resolveActor(String user) {
        if (!oidcEnabled) {
            return "anonymous";
        }
        if (user == null || user.isBlank() || "anonymousUser".equalsIgnoreCase(user)) {
            return "anonymous";
        }
        return user;
    }
}