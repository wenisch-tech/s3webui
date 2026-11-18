package tech.wenisch.s3webui.desktop;

/**
 * Plain entry point that does not extend {@link javafx.application.Application}, so the
 * desktop build also starts from the classpath (e.g. {@code java -cp ... DesktopLauncher}).
 */
public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        DesktopApplication.main(args);
    }
}
