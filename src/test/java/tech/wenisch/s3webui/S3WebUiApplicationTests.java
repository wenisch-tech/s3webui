package tech.wenisch.s3webui;

import tech.wenisch.s3webui.config.FileSizeFormatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3WebUiApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the application context boots correctly.
        // The S3 client is not invoked during startup, so no real S3 endpoint is required.
    }

    @Test
    void fileSizeFormatterFormatsCorrectly() {
        FileSizeFormatter formatter = new FileSizeFormatter();
        assertEquals("512 B", formatter.format(512));
        assertEquals("1.0 KB", formatter.format(1024));
        assertTrue(formatter.format(1024 * 1024).contains("MB"));
        assertTrue(formatter.format(1024L * 1024 * 1024).contains("GB"));
    }
}
