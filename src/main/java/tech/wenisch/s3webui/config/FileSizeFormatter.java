package tech.wenisch.s3webui.config;

import org.springframework.stereotype.Component;

@Component("fileSizeFormatter")
public class FileSizeFormatter {

    public String format(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }
}
