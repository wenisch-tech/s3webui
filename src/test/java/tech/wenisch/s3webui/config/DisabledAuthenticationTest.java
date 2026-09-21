package tech.wenisch.s3webui.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tech.wenisch.s3webui.repository.AppUserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(properties = {
        "DISABLE_AUTHENTICATION=true",
        "oidc.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:disabled-authentication-test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
class DisabledAuthenticationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AppUserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void visitorsAreVirtualAdministratorsWithoutASignIn() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("adminTabBtn-users"))));
        mockMvc.perform(get("/api/admin/credentials"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/iam/capabilities"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/credentials")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Admin-only key",
                                  "endpointUrl": "https://s3.example.com",
                                  "accessKey": "AKIA",
                                  "secretKey": "top-secret"
                                }"""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/s3/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentials[?(@.name == 'Admin-only key')]").exists());
    }

    @Test
    void localUserManagementAndLoginAreUnavailable() throws Exception {
        assertThat(userRepository.count()).isZero();

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }
}
