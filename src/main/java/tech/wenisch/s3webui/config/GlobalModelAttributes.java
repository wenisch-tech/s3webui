package tech.wenisch.s3webui.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Optional;

@ControllerAdvice
public class GlobalModelAttributes {

    private static final String RELEASE_PAGE_URL = "https://github.com/wenisch-tech/s3webui/releases";
    private static final String LICENSE_PAGE_URL = "https://github.com/wenisch-tech/s3webui/blob/main/LICENSE";

    @Value("${oidc.enabled:false}")
    private boolean oidcEnabled;

    @Value("${oidc.required-role:}")
    private String requiredRole;

    private final BuildProperties buildProperties;

    @Autowired
    public GlobalModelAttributes(ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.buildProperties = buildPropertiesProvider.getIfAvailable();
    }

    @ModelAttribute("oidcEnabled")
    public boolean oidcEnabled() {
        return oidcEnabled;
    }

    @ModelAttribute("oidcRequiredRole")
    public String oidcRequiredRole() {
        return requiredRole;
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
}
