package tech.wenisch.s3webui.desktop;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import tech.wenisch.s3webui.S3WebUiApplication;

/**
 * Experimental desktop shell: starts the regular Spring Boot application bound to
 * 127.0.0.1 on a random free port (single-user, authentication disabled) and shows it
 * in a JavaFX WebView.
 */
public class DesktopApplication extends Application {

    private ConfigurableApplicationContext context;
    private Stage stage;
    // JavaFX keeps only a weak reference to objects exposed to JavaScript, so hold on to it
    private DownloadBridge downloadBridge;

    public static void main(String[] args) {
        Application.launch(DesktopApplication.class, args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        VBox loading = new VBox(12, new ProgressIndicator(), new Label("Starting S3 Web UI…"));
        loading.setAlignment(Pos.CENTER);
        stage.setTitle("S3WEBUI");
        try (var icon = DesktopApplication.class.getResourceAsStream("/static/img/logo.png")) {
            if (icon != null) {
                stage.getIcons().add(new Image(icon));
            }
        } catch (IOException ignored) {
            // icon is cosmetic
        }
        stage.setScene(new Scene(new StackPane(loading), 1280, 800));
        stage.show();

        Thread boot = new Thread(this::boot, "s3webui-boot");
        boot.setDaemon(true);
        boot.start();
    }

    private void boot() {
        try {
            int port = freePort();
            Path dataDir = dataDir();
            // command-line args have the highest precedence, so they beat application.yml and env
            context = new SpringApplicationBuilder(S3WebUiApplication.class)
                    .run(
                            "--server.port=" + port,
                            // loopback only: authentication is disabled in desktop mode
                            "--server.address=127.0.0.1",
                            "--app.data-dir=" + dataDir,
                            "--authentication.disabled=true",
                            // keys typed in on first start are saved, nobody manages them as admin
                            "--app.auto-store-keys=true");
            Platform.runLater(() -> showWebView(port));
        } catch (Exception e) {
            Platform.runLater(() -> showError(e));
        }
    }

    private void showWebView(int port) {
        WebView web = new WebView();
        web.getEngine().getLoadWorker().exceptionProperty().addListener((o, a, b) -> {
            if (b != null) {
                System.err.println("WebView load error: " + b);
            }
        });
        web.getEngine().getLoadWorker().stateProperty().addListener((o, a, b) -> {
            if (b == Worker.State.FAILED) {
                System.err.println("WebView failed to load page");
            }
        });
        downloadBridge = new DownloadBridge(port, stage);
        web.getEngine().getLoadWorker().stateProperty().addListener((o, a, b) -> {
            if (b == Worker.State.SUCCEEDED) {
                // the page calls this for downloads, because WebView cannot save attachments itself
                ((JSObject) web.getEngine().executeScript("window")).setMember("desktopBridge", downloadBridge);
            }
        });
        web.getEngine().load("http://127.0.0.1:" + port + "/");
        stage.getScene().setRoot(new StackPane(web));
    }

    private void showError(Exception e) {
        Label label = new Label("Failed to start: " + e);
        label.setWrapText(true);
        stage.getScene().setRoot(new StackPane(label));
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
        Platform.exit();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static Path dataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null ? appData : home, "s3webui");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return xdg != null && !xdg.isBlank()
                ? Path.of(xdg, "s3webui")
                : Path.of(home, ".local", "share", "s3webui");
    }
}
