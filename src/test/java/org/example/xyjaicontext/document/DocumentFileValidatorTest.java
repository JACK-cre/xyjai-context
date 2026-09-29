package org.example.xyjaicontext.document;

import org.example.xyjaicontext.config.DocumentProcessingProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentFileValidatorTest {

    private final DocumentFileValidator validator = new DocumentFileValidator(
            new DocumentTypeDetector(), new DocumentProcessingProperties());

    @Test
    void acceptsSupportedTextDocumentAndSanitizesFileName() {
        DetectedDocumentType detected = validator.validate("../docs/readme.txt",
                "knowledge base".getBytes(StandardCharsets.UTF_8));

        assertThat(detected.fileName()).isEqualTo("readme.txt");
        assertThat(detected.mimeType()).isEqualTo("text/plain");
    }

    @Test
    void rejectsExtensionMimeMismatch() {
        assertThatThrownBy(() -> validator.validate("fake.pdf",
                "this is not a pdf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> validator.validate("payload.exe",
                "plain text".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("扩展名");
    }
}
