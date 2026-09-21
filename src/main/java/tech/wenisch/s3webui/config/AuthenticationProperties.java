package tech.wenisch.s3webui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Controls whether S3 Web UI performs application-level authentication.
 *
 * <p>When disabled, every request receives a virtual administrator identity. This is intended
 * only for deployments protected by a trusted network boundary or another access-control layer.
 */
@Component
@ConfigurationProperties(prefix = "authentication")
public class AuthenticationProperties {

    private boolean disabled;

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }
}
