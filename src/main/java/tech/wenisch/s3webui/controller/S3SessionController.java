package tech.wenisch.s3webui.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.s3webui.model.S3CredentialView;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;

import java.util.List;

/**
 * Drives the key picker shown after sign-in and the switcher in the navbar.
 */
@RestController
@RequestMapping("/api/s3/session")
@RequiredArgsConstructor
public class S3SessionController {

    private final S3ConnectionSettingsService s3ConnectionSettingsService;

    @GetMapping
    public SessionStatusResponse getStatus() {
        return new SessionStatusResponse(
                s3ConnectionSettingsService.isSelectionRequired(),
                s3ConnectionSettingsService.getActiveCredentialId(),
                s3ConnectionSettingsService.getActiveCredentialName(),
                s3ConnectionSettingsService.isUserSuppliedCredentialsAllowed(),
                s3ConnectionSettingsService.listAvailableCredentials()
        );
    }

    @PostMapping
    public ResponseEntity<Void> select(@RequestBody SelectionRequest request) {
        if (request.credentialId() != null && !request.credentialId().isBlank()) {
            s3ConnectionSettingsService.selectCredential(request.credentialId());
        } else {
            s3ConnectionSettingsService.selectOwnCredentials(
                    new S3ConnectionSettingsService.SubmittedS3Settings(
                            request.accessKey(),
                            request.secretKey(),
                            request.endpointUrl(),
                            request.region()));
        }
        return ResponseEntity.ok().build();
    }

    public record SelectionRequest(
            String credentialId,
            String accessKey,
            String secretKey,
            String endpointUrl,
            String region
    ) {
    }

    public record SessionStatusResponse(
            boolean selectionRequired,
            String activeCredentialId,
            String activeCredentialName,
            boolean allowOwnCredentials,
            List<S3CredentialView> credentials
    ) {
    }
}
