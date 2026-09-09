package tech.wenisch.s3webui.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * See {@code AdminApiControllerTest} for why MockMvc is built manually with {@code springSecurity()}.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:s3-session-test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
class S3SessionControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(username = "bob@example.com", roles = "USER")
    void ownCredentialsOmittingInsecureSkipTlsVerifyDoNotCrash() throws Exception {
        // Jackson 3 fails to bind a missing JSON property into a primitive record component, so
        // SelectionRequest.insecureSkipTlsVerify is boxed - this guards that a caller who omits it
        // (the field didn't exist at all until this fix) does not 500.
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/s3/session")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accessKey": "AKIA",
                                  "secretKey": "secret",
                                  "endpointUrl": "https://s3.example.com"
                                }"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/s3/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCredentialName").value("Own credentials"));
    }

    @Test
    @WithMockUser(username = "bob@example.com", roles = "USER")
    void ownCredentialsHonorTheInsecureSkipTlsVerifyFlag() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/s3/session")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accessKey": "AKIA",
                                  "secretKey": "secret",
                                  "endpointUrl": "https://s3.example.com",
                                  "insecureSkipTlsVerify": true
                                }"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/s3/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionRequired").value(false));
    }
}
