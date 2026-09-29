package org.example.xyjaicontext.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "agent.document.processing")
public class DocumentProcessingProperties {

    private long maxFileSizeBytes = 50L * 1024 * 1024;
    private List<String> allowedExtensions = new ArrayList<>(List.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "html", "htm", "rtf", "csv", "tsv", "json", "xml"));
    private int scannedPdfMinCharsPerPage = 20;
    private Chunking chunking = new Chunking();
    private Ocr ocr = new Ocr();

    @Data
    public static class Chunking {
        private int minChildTokens = 120;
        private int targetChildTokens = 400;
        private int maxChildTokens = 700;
        private int parentMaxTokens = 1600;
        private int overlapTokens = 60;
        private int tableRowsPerChunk = 30;
    }

    @Data
    public static class Ocr {
        private boolean enabled = false;
        private String command = "tesseract";
        private String languages = "chi_sim+eng";
        private int dpi = 200;
        private Duration pageTimeout = Duration.ofSeconds(60);
    }
}
