package tech.wenisch.s3webui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FrontendMigrationTest {

  @Test
  void templatesUseTheLocalTailwindAndAlpineFrontend() throws IOException {
    try (var files = Files.walk(Path.of("src/main/resources/templates"))) {
      for (Path file : files.filter(path -> path.toString().endsWith(".html")).toList()) {
        String html = Files.readString(file);
        assertThat(html).as(file.toString())
            .doesNotContain("/webjars/bootstrap", "data-bs-", "class=\"bi");
      }
    }
    assertThat(Files.readString(Path.of("pom.xml")))
        .doesNotContain("bootstrap-icons", "webjars-locator-lite");
  }

  @Test
  void bucketTemplatesUseNativeDialogsAndPreserveTheInteractiveViews() throws IOException {
    String buckets = Files.readString(Path.of("src/main/resources/templates/buckets.html"));
    String bucket = Files.readString(Path.of("src/main/resources/templates/bucket.html"));
    String frontend = Files.readString(Path.of("src/main/frontend/s3webui.js"));

    assertThat(buckets).contains("bucketCardsView", "bucketPieView", "bucketTableView",
        "createBucketModal", "deleteBucketModal");
    assertThat(bucket).contains("createFolderModal", "uploadModal", "renameModal",
        "deleteObjectModal", "folderNameError").doesNotContain("prompt(");
    assertThat(frontend).contains("data-theme", "openDialog", "closeDialog", "showToast");
  }
}
