package tech.wenisch.s3webui.desktop;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadRulesTest {

    @Test
    void onlyTheLocalDownloadEndpointsAreAllowed() {
        assertThat(DownloadRules.isAllowed(URI.create("http://127.0.0.1:5000/api/buckets/b/objects/download?key=a"), 5000)).isTrue();
        assertThat(DownloadRules.isAllowed(URI.create("http://127.0.0.1:5000/api/buckets/b/objects/zip?key=a"), 5000)).isTrue();
        assertThat(DownloadRules.isAllowed(URI.create("http://127.0.0.1:5001/api/buckets/b/objects/zip"), 5000)).isFalse();
        assertThat(DownloadRules.isAllowed(URI.create("http://evil.example/api/buckets/b/objects/zip"), 5000)).isFalse();
        assertThat(DownloadRules.isAllowed(URI.create("https://127.0.0.1:5000/api/buckets/b/objects/zip"), 5000)).isFalse();
        assertThat(DownloadRules.isAllowed(URI.create("http://127.0.0.1:5000/api/admin/users"), 5000)).isFalse();
    }

    @Test
    void anObjectIsSavedUnderItsFileName() {
        assertThat(DownloadRules.suggestFileName(
                URI.create("http://127.0.0.1:1/api/buckets/b/objects/download?key=reports%2F2026%2Fq1+final.pdf")))
                .isEqualTo("q1 final.pdf");
    }

    @Test
    void aSingleFolderIsZippedUnderItsName() {
        assertThat(DownloadRules.suggestFileName(
                URI.create("http://127.0.0.1:1/api/buckets/b/objects/zip?key=photos%2F2026%2F")))
                .isEqualTo("2026.zip");
    }

    @Test
    void aMixedSelectionIsZippedUnderTheBucketName() {
        assertThat(DownloadRules.suggestFileName(
                URI.create("http://127.0.0.1:1/api/buckets/my%20bucket/objects/zip?key=a.txt&key=b.txt")))
                .isEqualTo("my bucket.zip");
    }

    @Test
    void unsafeCharactersAreReplaced() {
        assertThat(DownloadRules.suggestFileName(
                URI.create("http://127.0.0.1:1/api/buckets/b/objects/download?key=a%3Ab%3F.txt")))
                .isEqualTo("a_b_.txt");
    }
}
