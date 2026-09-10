package tech.wenisch.s3webui.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tech.wenisch.s3webui.repository.S3CredentialRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc is built manually with {@code springSecurity()} rather than via {@code @AutoConfigureMockMvc},
 * because Spring Boot 4's modularized {@code spring-boot-webmvc-test} module dropped the
 * {@code MockMvcSecurityConfiguration} glue that used to wire the security filter chain into MockMvc
 * automatically - without it, {@code @WithMockUser} is silently ignored and every request 302s to
 * {@code /login} as if signed out.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-api-test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
class AdminApiControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private S3CredentialRepository credentialRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(username = "admin@s3webui.local", roles = "ADMIN")
    void administratorsCanCreateAKeyAndTheSecretIsNeverReturned() throws Exception {
        mockMvc.perform(post("/api/admin/credentials")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Archive",
                                  "endpointUrl": "https://s3.example.com",
                                  "region": "eu-central-1",
                                  "accessKey": "AKIA",
                                  "secretKey": "top-secret",
                                  "enabled": true,
                                  "grants": [{"grantType": "ALL_AUTHENTICATED"}]
                                }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Archive"))
                .andExpect(jsonPath("$.secretKey").doesNotExist())
                .andExpect(jsonPath("$.grants[0].grantType").value("ALL_AUTHENTICATED"));

        assertThat(credentialRepository.findByNameIgnoreCase("Archive"))
                .get()
                .extracting(credential -> credential.getSecretKey())
                .isEqualTo("top-secret");
    }

    @Test
    @WithMockUser(username = "admin@s3webui.local", roles = "ADMIN")
    void anInvalidKeyIsRejectedWithABadRequest() throws Exception {
        mockMvc.perform(post("/api/admin/credentials")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\", \"endpointUrl\": \"https://s3.example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin@s3webui.local", roles = "ADMIN")
    void aKeyRequestOmittingBooleanFieldsStillCreatesTheKey() throws Exception {
        // Jackson 3 fails to bind a missing JSON property into a primitive record component, so
        // CredentialRequest boxes insecureSkipTlsVerify/enabled - this guards that omitting them
        // from the request (as a minimal API caller would) does not 500.
        mockMvc.perform(post("/api/admin/credentials")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Minimal",
                                  "endpointUrl": "https://s3.example.com",
                                  "accessKey": "AKIA",
                                  "secretKey": "top-secret"
                                }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insecureSkipTlsVerify").value(false))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @WithMockUser(username = "admin@s3webui.local", roles = "ADMIN")
    void aRejectedApiCallAnswersWithJsonForbiddenRatherThanTheHtmlPage() throws Exception {
        // Without .with(csrf()) the CSRF filter rejects the call. Forwarding that to the GET-only
        // access denied page used to surface as a confusing 405.
        mockMvc.perform(post("/api/admin/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @WithMockUser(username = "bob@example.com", roles = "USER")
    void ordinaryUsersAreRefused() throws Exception {
        mockMvc.perform(get("/api/admin/credentials")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/iam/capabilities")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/iam/users")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/settings")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/s3/session")).andExpect(status().isOk());
    }

    @Test
    void signedOutCallersAreSentToTheLoginPage() throws Exception {
        mockMvc.perform(get("/api/admin/credentials")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/")).andExpect(status().is3xxRedirection());
    }

    @Test
    void kubernetesCanReachEveryHealthCheckWithoutSigningIn() throws Exception {
        // Liveness and readiness must stay reachable by an unauthenticated kubelet, or every probe
        // 302s to /login and Kubernetes kills the pod believing it is unhealthy.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin@s3webui.local", roles = "ADMIN")
    void theDefaultAdministratorIsSeededOnFirstStart() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'admin@s3webui.local')].role").value("ADMIN"));
    }

    @Test
    @WithMockUser(username = "admin@s3webui.local", roles = "ADMIN")
    void ownCredentialsAreAllowedByDefault() throws Exception {
        mockMvc.perform(get("/api/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowUserSuppliedCredentials").value(true));
    }
}
