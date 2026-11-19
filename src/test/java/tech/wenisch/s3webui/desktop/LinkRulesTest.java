package tech.wenisch.s3webui.desktop;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkRulesTest {

    @Test
    void onlyTheLocalAppIsInternal() {
        assertThat(LinkRules.isInternal("http://127.0.0.1:5000/buckets", 5000)).isTrue();
        assertThat(LinkRules.isInternal("http://127.0.0.1:5001/buckets", 5000)).isFalse();
        assertThat(LinkRules.isInternal("https://s3webui.wenisch.tech", 5000)).isFalse();
        assertThat(LinkRules.isInternal("http://localhost:5000/", 5000)).isFalse();
        assertThat(LinkRules.isInternal(null, 5000)).isFalse();
    }

    @Test
    void onlyWebAndMailLinksGoToTheSystem() {
        assertThat(LinkRules.isOpenableExternally("https://s3webui.wenisch.tech")).isTrue();
        assertThat(LinkRules.isOpenableExternally("http://example.com")).isTrue();
        assertThat(LinkRules.isOpenableExternally("mailto:me@example.com")).isTrue();
        assertThat(LinkRules.isOpenableExternally("file:///etc/passwd")).isFalse();
        assertThat(LinkRules.isOpenableExternally("javascript:alert(1)")).isFalse();
        assertThat(LinkRules.isOpenableExternally("not a url")).isFalse();
        assertThat(LinkRules.isOpenableExternally(null)).isFalse();
    }
}
