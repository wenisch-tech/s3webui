package tech.wenisch.s3webui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Switches the IAM management section off entirely. On by default - whether IAM is actually
 * usable is decided at runtime by probing the provider, so a deployment against a backend
 * without an IAM API needs no configuration. Set it to false to keep the client out of the
 * context altogether.
 */
@Component
@ConfigurationProperties(prefix = "iam")
public class IamProperties {

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
