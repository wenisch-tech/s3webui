package tech.wenisch.s3webui.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.s3webui.entity.AppUser;
import tech.wenisch.s3webui.entity.AuthProvider;
import tech.wenisch.s3webui.entity.GrantType;
import tech.wenisch.s3webui.entity.S3Credential;
import tech.wenisch.s3webui.entity.S3CredentialGrant;
import tech.wenisch.s3webui.entity.UserRole;
import tech.wenisch.s3webui.service.AppSettingsService;
import tech.wenisch.s3webui.service.AuthenticationIdentity;
import tech.wenisch.s3webui.service.S3CredentialService;
import tech.wenisch.s3webui.service.UserService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JSON backend for the administrator settings panel. Locked to {@code ROLE_ADMIN} by
 * {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminApiController {

    private final S3CredentialService credentialService;
    private final UserService userService;
    private final AppSettingsService appSettingsService;

    // ── S3 keys ──────────────────────────────────────────────────────────────

    @GetMapping("/credentials")
    public List<CredentialResponse> listCredentials() {
        return credentialService.listAll().stream().map(AdminApiController::toResponse).toList();
    }

    @PostMapping("/credentials")
    public CredentialResponse createCredential(@RequestBody CredentialRequest request,
                                               Authentication authentication) {
        String createdBy = AuthenticationIdentity.emailOf(authentication);
        return toResponse(credentialService.create(request.toForm(), createdBy));
    }

    @PutMapping("/credentials/{id}")
    public CredentialResponse updateCredential(@PathVariable Long id, @RequestBody CredentialRequest request) {
        return toResponse(credentialService.update(id, request.toForm()));
    }

    @DeleteMapping("/credentials/{id}")
    public ResponseEntity<Void> deleteCredential(@PathVariable Long id) {
        credentialService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Users ────────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public List<UserResponse> listUsers() {
        return userService.listUsers().stream().map(AdminApiController::toResponse).toList();
    }

    @PostMapping("/users")
    public UserResponse createUser(@RequestBody CreateUserRequest request) {
        if (request.password() == null || request.password().isBlank()) {
            throw new IllegalArgumentException("A password is required");
        }
        return toResponse(userService.createUser(request.email(), request.password(), request.roleOrDefault()));
    }

    @PutMapping("/users/{id}")
    public UserResponse updateUser(@PathVariable Long id, @RequestBody UpdateUserRequest request) {
        return toResponse(userService.updateUser(id, request.role(), request.enabled(), request.displayName()));
    }

    @PostMapping("/users/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @RequestBody ResetPasswordRequest request) {
        if (request.password() == null || request.password().isBlank()) {
            throw new IllegalArgumentException("A password is required");
        }
        userService.resetPassword(id, request.password());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // ── General settings ─────────────────────────────────────────────────────

    @GetMapping("/settings")
    public SettingsResponse getSettings() {
        return new SettingsResponse(appSettingsService.isUserSuppliedCredentialsAllowed());
    }

    @PutMapping("/settings")
    public SettingsResponse updateSettings(@RequestBody SettingsResponse request) {
        appSettingsService.setUserSuppliedCredentialsAllowed(Boolean.TRUE.equals(request.allowUserSuppliedCredentials()));
        return getSettings();
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private static CredentialResponse toResponse(S3Credential credential) {
        return new CredentialResponse(
                credential.getId(),
                credential.getName(),
                credential.getEndpointUrl(),
                credential.getRegion(),
                credential.getAccessKey(),
                credential.isInsecureSkipTlsVerify(),
                credential.isEnabled(),
                credential.getCreatedAt(),
                credential.getCreatedBy(),
                credential.getGrants().stream()
                        .map(AdminApiController::toResponse)
                        .toList());
    }

    private static GrantResponse toResponse(S3CredentialGrant grant) {
        return new GrantResponse(grant.getId(), grant.getGrantType(), grant.getGrantValue());
    }

    private static UserResponse toResponse(AppUser user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getProvider(),
                user.isEnabled(),
                user.getLastLoginAt(),
                user.getCreatedAt());
    }

    // ── Payloads ─────────────────────────────────────────────────────────────

    /**
     * The secret key is never returned; a blank one on update keeps the stored value. Boolean fields
     * are boxed rather than primitive so a caller who omits one gets a sensible default instead of a
     * 500 - Jackson fails to bind {@code null} into a primitive when a JSON property is absent.
     */
    public record CredentialRequest(
            String name,
            String endpointUrl,
            String region,
            String accessKey,
            String secretKey,
            Boolean insecureSkipTlsVerify,
            Boolean enabled,
            List<GrantRequest> grants
    ) {
        S3CredentialService.CredentialForm toForm() {
            List<S3CredentialService.GrantForm> grantForms = grants == null
                    ? List.of()
                    : grants.stream()
                    .map(grant -> new S3CredentialService.GrantForm(grant.grantType(), grant.grantValue()))
                    .toList();
            return new S3CredentialService.CredentialForm(
                    name, endpointUrl, region, accessKey, secretKey,
                    Boolean.TRUE.equals(insecureSkipTlsVerify), enabled == null || enabled, grantForms);
        }
    }

    public record GrantRequest(GrantType grantType, String grantValue) {
    }

    public record CredentialResponse(
            Long id,
            String name,
            String endpointUrl,
            String region,
            String accessKey,
            boolean insecureSkipTlsVerify,
            boolean enabled,
            LocalDateTime createdAt,
            String createdBy,
            List<GrantResponse> grants
    ) {
    }

    public record GrantResponse(Long id, GrantType grantType, String grantValue) {
    }

    public record CreateUserRequest(String email, String password, UserRole role) {
        UserRole roleOrDefault() {
            return role == null ? UserRole.USER : role;
        }
    }

    public record UpdateUserRequest(UserRole role, Boolean enabled, String displayName) {
    }

    public record ResetPasswordRequest(String password) {
    }

    public record UserResponse(
            Long id,
            String email,
            String displayName,
            UserRole role,
            AuthProvider provider,
            boolean enabled,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt
    ) {
    }

    public record SettingsResponse(Boolean allowUserSuppliedCredentials) {
    }
}
