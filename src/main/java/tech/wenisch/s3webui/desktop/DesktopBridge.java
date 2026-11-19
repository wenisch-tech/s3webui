package tech.wenisch.s3webui.desktop;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Exposed to the page as {@code window.desktopBridge}.
 *
 * <p>Downloads: JavaFX's WebView ignores "Content-Disposition: attachment", so the page hands download URLs
 * to this class, which asks where to save the file and streams it there. The request reuses the WebView's
 * session cookie, so the selected S3 key applies exactly as in the browser.
 *
 * <p>External links: anything outside the app opens in the default browser instead of the app window.
 */
public class DesktopBridge {

    private final int port;
    private final Window owner;
    private final HostServices hostServices;
    private File lastDirectory;

    DesktopBridge(int port, Window owner, HostServices hostServices) {
        this.port = port;
        this.owner = owner;
        this.hostServices = hostServices;
    }

    /** Called from JavaScript and from the WebView fallbacks: opens a link in the default browser. */
    public void openExternal(String url) {
        if (LinkRules.isOpenableExternally(url)) {
            Platform.runLater(() -> hostServices.showDocument(url));
        }
    }

    /** Called from JavaScript. */
    public void download(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!DownloadRules.isAllowed(uri, port)) {
            return;
        }
        // Leave the JavaScript callback before opening a modal dialog.
        Platform.runLater(() -> chooseTargetAndSave(uri));
    }

    private void chooseTargetAndSave(URI uri) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save file");
        chooser.setInitialFileName(DownloadRules.suggestFileName(uri));
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        File target = chooser.showSaveDialog(owner);
        if (target == null) {
            return;
        }
        lastDirectory = target.getParentFile();

        Thread worker = new Thread(() -> save(uri, target.toPath()), "s3webui-download");
        worker.setDaemon(true);
        worker.start();
    }

    private void save(URI uri, Path target) {
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpClient.Builder client = HttpClient.newBuilder();
            CookieHandler cookies = CookieHandler.getDefault();
            if (cookies != null) {
                client.cookieHandler(cookies);
            }
            HttpResponse<InputStream> response = client.build().send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    String detail = new String(body.readNBytes(500), StandardCharsets.UTF_8);
                    throw new IOException("Server answered " + response.statusCode() + ": " + detail);
                }
                Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            notify(Alert.AlertType.INFORMATION, "Saved to " + target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // nothing more to clean up
            }
            notify(Alert.AlertType.ERROR, "Download failed: " + e.getMessage());
        }
    }

    private void notify(Alert.AlertType type, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type, message);
            alert.initOwner(owner);
            alert.setHeaderText(null);
            alert.setTitle("S3WEBUI");
            alert.show();
        });
    }
}
