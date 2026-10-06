package tech.wenisch.s3webui.controller;

import org.junit.jupiter.api.Test;
import tech.wenisch.s3webui.service.GlobalSearchService;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalSearchApiControllerTest {

    @Test
    void returnsSearchMetadataWithoutAllowingResponseCaching() {
        GlobalSearchService service = mock(GlobalSearchService.class);
        var page = new GlobalSearchService.SearchPage(
                List.of(), 6, true, Instant.parse("2026-10-06T09:00:00Z"), true, List.of("private"));
        when(service.search("report", 0, 5, false)).thenReturn(page);
        GlobalSearchApiController controller = new GlobalSearchApiController(service);

        var response = controller.search("report", 5, false);

        assertEquals(page, response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).search("report", 0, 5, false);
    }

    @Test
    void rejectsLimitsOutsideThePublicApiRange() {
        GlobalSearchApiController controller = new GlobalSearchApiController(mock(GlobalSearchService.class));

        assertThrows(IllegalArgumentException.class, () -> controller.search("report", 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> controller.search("report", GlobalSearchService.MAX_RESULT_LIMIT + 1, false));
    }
}
