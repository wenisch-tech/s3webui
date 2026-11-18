package tech.wenisch.s3webui.desktop;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Which URLs the desktop shell may save to disk, and what to call the file. No JavaFX in here. */
final class DownloadRules {

    private static final Pattern DOWNLOAD_PATH =
            Pattern.compile(".*/api/buckets/([^/]+)/objects/(download|zip)$");

    private DownloadRules() {
    }

    /** Only the app's own object and ZIP endpoints on the local server may be downloaded. */
    static boolean isAllowed(URI uri, int port) {
        return uri != null
                && "http".equals(uri.getScheme())
                && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() == port
                && uri.getPath() != null
                && DOWNLOAD_PATH.matcher(uri.getPath()).matches();
    }

    /** Mirrors the server's naming: the object's file name, or the folder / bucket name plus ".zip". */
    static String suggestFileName(URI uri) {
        Matcher path = DOWNLOAD_PATH.matcher(uri.getPath());
        if (!path.matches()) {
            return "download";
        }
        List<String> keys = queryValues(uri.getRawQuery(), "key");
        boolean zip = "zip".equals(path.group(2));

        String name;
        if (!zip) {
            name = lastSegment(keys.isEmpty() ? "" : keys.get(0));
        } else if (keys.size() == 1) {
            name = lastSegment(keys.get(0)) + ".zip";
        } else {
            name = decode(path.group(1)) + ".zip";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return name.isEmpty() || name.equals(".zip") ? (zip ? "download.zip" : "download") : name;
    }

    private static String lastSegment(String key) {
        String trimmed = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private static List<String> queryValues(String rawQuery, String name) {
        List<String> values = new ArrayList<>();
        if (rawQuery == null) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && decode(pair.substring(0, eq)).equals(name)) {
                values.add(decode(pair.substring(eq + 1)));
            }
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
