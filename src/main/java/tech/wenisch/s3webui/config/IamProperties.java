package tech.wenisch.s3webui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Toggles the optional IAM management section. Off by default: it is only useful against a
 * provider that actually exposes an IAM API, and it hands admins a lot of rope.
 */
@Component
@ConfigurationProperties(prefix = "iam")
public class IamProperties {

    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
