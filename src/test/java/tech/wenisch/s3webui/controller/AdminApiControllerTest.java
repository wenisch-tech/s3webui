package tech.wenisch.s3webui.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tech.wenisch.s3webui.repository.S3CredentialRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-api-test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
@AutoConfigureMockMvc
class AdminApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private S3CredentialRepository credentialRepository;

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
        mockMvc.perform(get("/admin/settings")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/s3/session")).andExpect(status().isOk());
    }

    @Test
    void signedOutCallersAreSentToTheLoginPage() throws Exception {
        mockMvc.perform(get("/api/admin/credentials")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/")).andExpect(status().is3xxRedirection());
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
