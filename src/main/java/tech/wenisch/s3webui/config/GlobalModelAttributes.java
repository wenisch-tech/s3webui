package tech.wenisch.s3webui.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

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
    public java.util.List<OidcProperties.LoginProviderView> oidcProviders() {
        return oidcProperties.getLoginProviders();
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
    public String s3BreadcrumbLabel() {
        String endpointUrl = s3ConnectionSettingsService.getStatus().endpointUrl();
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return "S3";
        }
        return endpointUrl;
    }
}
