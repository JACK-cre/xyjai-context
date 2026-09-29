package org.example.xyjaicontext.document;

import lombok.RequiredArgsConstructor;
import org.example.xyjaicontext.config.DocumentProcessingProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DocumentFileValidator {

    private static final Set<String> BINARY_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/rtf");

    private final DocumentTypeDetector typeDetector;
    private final DocumentProcessingProperties properties;

    public DetectedDocumentType validate(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (content.length > properties.getMaxFileSizeBytes()) {
            throw new IllegalArgumentException("文件超过允许的最大大小");
        }

        DetectedDocumentType detected = typeDetector.detect(fileName, content);
        boolean allowedExtension = properties.getAllowedExtensions().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(detected.extension()::equals);
        if (!allowedExtension) {
            throw new IllegalArgumentException("不支持的文件扩展名: " + detected.extension());
        }
        if (!isAllowedMimeType(detected.mimeType())) {
            throw new IllegalArgumentException("不支持的文件类型: " + detected.mimeType());
        }
        if (!isCompatible(detected.extension(), detected.mimeType())) {
            throw new IllegalArgumentException("文件扩展名与实际类型不匹配: ."
                    + detected.extension() + " / " + detected.mimeType());
        }
        return detected;
    }

    private boolean isAllowedMimeType(String mimeType) {
        return mimeType != null && (mimeType.startsWith("text/")
                || mimeType.endsWith("+xml")
                || mimeType.equals("application/xml")
                || mimeType.equals("application/json")
                || BINARY_MIME_TYPES.contains(mimeType));
    }

    private boolean isCompatible(String extension, String mimeType) {
        return switch (extension) {
            case "pdf" -> mimeType.equals("application/pdf");
            case "doc" -> mimeType.equals("application/msword");
            case "docx" -> mimeType.equals(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "xls" -> mimeType.equals("application/vnd.ms-excel");
            case "xlsx" -> mimeType.equals(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "ppt" -> mimeType.equals("application/vnd.ms-powerpoint");
            case "pptx" -> mimeType.equals(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");
            case "html", "htm" -> mimeType.equals("text/html") || mimeType.equals("application/xhtml+xml");
            case "rtf" -> mimeType.equals("application/rtf") || mimeType.equals("text/rtf");
            case "json" -> mimeType.equals("application/json") || mimeType.equals("text/plain");
            case "xml" -> mimeType.equals("application/xml") || mimeType.equals("text/xml")
                    || mimeType.endsWith("+xml");
            case "csv" -> mimeType.equals("text/csv") || mimeType.equals("text/plain");
            case "tsv" -> mimeType.equals("text/tab-separated-values") || mimeType.equals("text/plain");
            case "md" -> mimeType.equals("text/markdown") || mimeType.equals("text/x-web-markdown")
                    || mimeType.equals("text/plain");
            case "txt" -> mimeType.equals("text/plain");
            default -> false;
        };
    }
}
