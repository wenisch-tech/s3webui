package tech.wenisch.s3webui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "DISABLE_AUTHENTICATION=true",
        "spring.datasource.url=jdbc:h2:mem:context-path-test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
class ContextPathIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/s3webui", "/foo/bar"})
    void templatesReceiveTheRuntimeContextPath(String contextPath) throws Exception {
        mockMvc.perform(get(contextPath + "/").contextPath(contextPath))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "<meta name=\"s3webui-base-path\" content=\"" + contextPath + "\"/>")))
                .andExpect(content().string(containsString(
                        "<link rel=\"icon\" type=\"image/png\" href=\"" + contextPath + "/img/logo.png\"/>")))
                .andExpect(content().string(not(containsString("href=\"/img/logo.png\""))));
    }

    @Test
    void apiRequestsRemainRelativeToTheContextPath() throws Exception {
        mockMvc.perform(get("/nested/api/admin/credentials").contextPath("/nested"))
                .andExpect(status().isOk());
    }
}
