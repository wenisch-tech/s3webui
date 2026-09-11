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
    assertThat(Files.readString(Path.of("package.json")))
        .contains("esbuild src/main/frontend/s3webui.js")
        .doesNotContain("node ./node_modules/esbuild/bin/esbuild");
  }

  @Test
  void bucketTemplatesUseNativeDialogsAndPreserveTheInteractiveViews() throws IOException {
    String buckets = Files.readString(Path.of("src/main/resources/templates/buckets.html"));
    String bucket = Files.readString(Path.of("src/main/resources/templates/bucket.html"));
    String frontend = Files.readString(Path.of("src/main/frontend/s3webui.js"));

    assertThat(buckets).contains("bucketCardsView", "bucketPieView", "bucketTableView",
        "createBucketModal", "deleteBucketModal");
    assertThat(bucket).contains("createFolderModal", "uploadModal", "renameModal",
        "deleteObjectModal", "folderNameError",
        "bucketPolicyModal", "bucketCorsModal", "json-editor").doesNotContain("prompt(");
    assertThat(frontend).contains("data-theme", "openDialog", "closeDialog", "showToast",
        "createJsonEditor", "hidePageLoading");
    assertThat(Files.readString(Path.of("package.json"))).contains("@codemirror/lang-json");
  }

  @Test
  void theIamSectionLivesInItsOwnFragmentAndIsWiredIntoSettings() throws IOException {
    String settings = Files.readString(Path.of("src/main/resources/templates/admin/settings.html"));
    String iam = Files.readString(Path.of("src/main/resources/templates/admin/fragments/iam.html"));

    assertThat(settings).contains("tab-iam", "adminTabBtn-iam", "admin/fragments/iam :: panel",
        "admin/fragments/iam :: script");
    // The tab is rendered unconditionally; the panel disables itself when the backend cannot
    // do IAM. Gating it server-side is what made the feature look absent entirely.
    assertThat(settings).doesNotContain("${iamEnabled}");
    assertThat(iam)
        .contains("iamUserModal", "iamGroupModal", "iamPolicyModal", "iamAttachModal",
            "iamMembersModal", "iamKeysModal", "iamInlinePolicyModal", "is-disabled",
            "json-editor", "refreshIcons();")
        .doesNotContain("prompt(", "confirm(");
  }

  @Test
  void administrationUsesTheRealSettingsRouteAndRefreshesDynamicIcons() throws IOException {
    String shell = Files.readString(Path.of("src/main/resources/templates/fragments/shell.html"));
    String settings = Files.readString(Path.of("src/main/resources/templates/admin/settings.html"));

    assertThat(shell)
        .contains("@{/admin/settings}")
        .doesNotContain("@{/admin}");
    assertThat(settings).contains("renderCredentials", "renderUsers", "refreshIcons();");
  }
}
