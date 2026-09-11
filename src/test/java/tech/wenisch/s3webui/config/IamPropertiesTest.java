package tech.wenisch.s3webui.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The IAM section shipped switched off, so nothing about it appeared in the UI and there was no
 * way to tell the feature existed. It is on by default now, with runtime detection deciding what
 * is usable - this pins that default.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:iam-defaults;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
class IamPropertiesTest {

    @Autowired
    private IamProperties iamProperties;

    @Test
    void iamIsEnabledUnlessTheDeploymentOptsOut() {
        assertThat(iamProperties.isEnabled())
                .as("IAM_ENABLED is unset here, which is how most deployments run")
                .isTrue();
    }

    @Test
    void theBareDefaultOnThePropertiesObjectMatchesTheYaml() {
        assertThat(new IamProperties().isEnabled()).isTrue();
    }
}
