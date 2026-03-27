package tech.wenisch.s3webui.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelAttributes {

    @Value("${oidc.enabled:false}")
    private boolean oidcEnabled;

    @Value("${oidc.required-role:}")
    private String requiredRole;

    @ModelAttribute("oidcEnabled")
    public boolean oidcEnabled() {
        return oidcEnabled;
    }

    @ModelAttribute("oidcRequiredRole")
    public String oidcRequiredRole() {
        return requiredRole;
    }
}
