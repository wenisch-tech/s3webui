package tech.wenisch.s3webui.config;

import org.springframework.stereotype.Component;

@Component("fileIconHelper")
public class FileIconHelper {

    public String icon(String filename) {
        if (filename == null) return "bi-file-earmark";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "bi-file-earmark-pdf text-danger";
        if (lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".gz")
                || lower.endsWith(".7z") || lower.endsWith(".rar")) return "bi-file-earmark-zip text-warning";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".webp"))
            return "bi-file-earmark-image text-success";
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi")
                || lower.endsWith(".mkv")) return "bi-file-earmark-play text-primary";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac"))
            return "bi-file-earmark-music text-info";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "bi-file-earmark-word text-primary";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "bi-file-earmark-excel text-success";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "bi-file-earmark-ppt text-warning";
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return "bi-file-earmark-text text-muted";
        if (lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".yaml")
                || lower.endsWith(".yml") || lower.endsWith(".toml")) return "bi-file-earmark-code text-secondary";
        if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".py")
                || lower.endsWith(".java") || lower.endsWith(".js") || lower.endsWith(".ts")
                || lower.endsWith(".go") || lower.endsWith(".rs")) return "bi-file-earmark-code text-secondary";
        return "bi-file-earmark text-muted";
    }
}
