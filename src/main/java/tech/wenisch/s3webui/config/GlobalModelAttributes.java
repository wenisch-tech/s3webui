package tech.wenisch.s3webui.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import tech.wenisch.s3webui.model.S3CredentialView;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;

import java.util.List;
import java.util.Optional;

@ControllerAdvice
public class GlobalModelAttributes {

    private static final String RELEASE_PAGE_URL = "https://github.com/wenisch-tech/s3webui/releases";
    private static final String LICENSE_PAGE_URL = "https://github.com/wenisch-tech/s3webui/blob/main/LICENSE";

    private final BuildProperties buildProperties;
    private final S3ConnectionSettingsService s3ConnectionSettingsService;
    private final OidcProperties oidcProperties;

    @Autowired
    public GlobalModelAttributes(
            ObjectProvider<BuildProperties> buildPropertiesProvider,
            S3ConnectionSettingsService s3ConnectionSettingsService,
            OidcProperties oidcProperties) {
        this.buildProperties = buildPropertiesProvider.getIfAvailable();
        this.s3ConnectionSettingsService = s3ConnectionSettingsService;
        this.oidcProperties = oidcProperties;
    }

    @ModelAttribute("oidcEnabled")
    public boolean oidcEnabled() {
        return oidcProperties.isEnabled();
    }

    @ModelAttribute("oidcRequiredRole")
    public String oidcRequiredRole() {
        return oidcProperties.getRequiredRole();
    }

    @ModelAttribute("oidcProviders")
    public List<OidcProperties.LoginProviderView> oidcProviders() {
        return oidcProperties.getLoginProviders();
    }

    @ModelAttribute("isAdmin")
    public boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }

    /** The key the current session browses with, for the navbar switcher. */
    @ModelAttribute("activeCredentialName")
    public String activeCredentialName(Authentication authentication) {
        return isSignedIn(authentication) ? s3ConnectionSettingsService.getActiveCredentialName() : null;
    }

    @ModelAttribute("availableCredentials")
    public List<S3CredentialView> availableCredentials(Authentication authentication) {
        return isSignedIn(authentication) ? s3ConnectionSettingsService.listAvailableCredentials() : List.of();
    }

    @ModelAttribute("appVersion")
    public String appVersion() {
        return Optional.ofNullable(buildProperties)
                .map(BuildProperties::getVersion)
                .orElse("dev");
    }

    @ModelAttribute("releasePageUrl")
    public String releasePageUrl() {
        return RELEASE_PAGE_URL;
    }

    @ModelAttribute("licensePageUrl")
    public String licensePageUrl() {
        return LICENSE_PAGE_URL;
    }

    @ModelAttribute("s3BreadcrumbLabel")
    public String s3BreadcrumbLabel(Authentication authentication) {
        String name = activeCredentialName(authentication);
        return name == null || name.isBlank() ? "S3" : name;
    }

    private boolean isSignedIn(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName());
    }
}
