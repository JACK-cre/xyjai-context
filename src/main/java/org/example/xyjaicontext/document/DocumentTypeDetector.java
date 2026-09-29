package org.example.xyjaicontext.document;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class DocumentTypeDetector {

    private final Tika tika = new Tika();

    public DetectedDocumentType detect(String originalFileName, byte[] content) {
        String fileName = sanitizeFileName(originalFileName);
        String mimeType = tika.detect(content, fileName);
        return new DetectedDocumentType(fileName, extension(fileName), mimeType);
    }

    private String sanitizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "unknown";
        }
        String normalized = originalFileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return (slash >= 0 ? normalized.substring(slash + 1) : normalized).trim();
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? ""
                : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
