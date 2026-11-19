package tech.wenisch.s3webui.desktop;

import java.net.URI;
import java.util.Set;

/** Decides which links stay in the app window and which go to the default browser. No JavaFX in here. */
final class LinkRules {

    private static final Set<String> EXTERNAL_SCHEMES = Set.of("http", "https", "mailto");

    private LinkRules() {
    }

    /** True for pages served by the app itself. */
    static boolean isInternal(String url, int port) {
        try {
            URI uri = URI.create(url);
            return "http".equals(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() == port;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    /** Only web and mail links are handed to the system; anything else (file:, javascript:, ...) is dropped. */
    static boolean isOpenableExternally(String url) {
        try {
            String scheme = URI.create(url).getScheme();
            return scheme != null && EXTERNAL_SCHEMES.contains(scheme.toLowerCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
