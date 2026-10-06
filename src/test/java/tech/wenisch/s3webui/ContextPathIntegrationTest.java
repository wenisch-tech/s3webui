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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tech.wenisch.s3webui.model.BucketDto;
import tech.wenisch.s3webui.model.S3ObjectDto;
import tech.wenisch.s3webui.service.S3Service;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "DISABLE_AUTHENTICATION=true",
        "spring.datasource.url=jdbc:h2:mem:context-path-test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
class ContextPathIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private S3Service s3Service;

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
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .containsIgnoringWhitespaces(
                                "<meta name=\"s3webui-base-path\" content=\"" + contextPath + "\"/>",
                                "<link rel=\"icon\" type=\"image/png\" href=\"" + contextPath + "/img/logo.png\"/>"))
                .andExpect(content().string(not(containsString("href=\"/img/logo.png\""))));
    }

    @Test
    void apiRequestsRemainRelativeToTheContextPath() throws Exception {
        mockMvc.perform(get("/nested/api/admin/credentials").contextPath("/nested"))
                .andExpect(status().isOk());
    }

    @Test
    void searchResultsTemplateRendersWhenNoS3KeyIsSelected() throws Exception {
        mockMvc.perform(get("/search").param("q", "report"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Search results")))
                .andExpect(content().string(containsString("No S3 key selected")));
    }

    @Test
    void globalSearchApiAndPopulatedResultsPageRenderForTheCurrentSession() throws Exception {
        when(s3Service.listBuckets()).thenReturn(List.of(BucketDto.builder().name("bucket-a").build()));
        when(s3Service.listAllObjects("bucket-a")).thenReturn(List.of(S3ObjectDto.builder()
                .key("docs/report.pdf")
                .name("report.pdf")
                .size(42)
                .build()));
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

        mockMvc.perform(get("/api/search").session(session).param("q", "report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].bucket").value("bucket-a"))
                .andExpect(jsonPath("$.results[0].key").value("docs/report.pdf"));

        mockMvc.perform(get("/api/search").session(session).param("q", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("at least 2")));

        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("globalSearchInput")));

        mockMvc.perform(get("/search").session(session).param("q", "report"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("report.pdf")))
                .andExpect(content().string(containsString("bucket-a")))
                .andExpect(content().string(containsString("highlight=docs/report.pdf")));
    }
}
