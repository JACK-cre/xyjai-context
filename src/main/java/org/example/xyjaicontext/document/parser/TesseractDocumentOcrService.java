package org.example.xyjaicontext.document.parser;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.example.xyjaicontext.config.DocumentProcessingProperties;
import org.example.xyjaicontext.document.model.DocumentElement;
import org.example.xyjaicontext.document.model.ParseRequest;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class TesseractDocumentOcrService implements DocumentOcrService {

    private final DocumentProcessingProperties properties;

    @Override
    public boolean isAvailable() {
        return properties.getOcr().isEnabled();
    }

    @Override
    public List<DocumentElement> extract(ParseRequest request, int pageCount) throws Exception {
        DocumentProcessingProperties.Ocr ocr = properties.getOcr();
        if (!ocr.isEnabled()) {
            return List.of();
        }
        List<DocumentElement> elements = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(request.content())) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                BufferedImage image = renderer.renderImageWithDPI(
                        pageIndex, Math.max(72, ocr.getDpi()), ImageType.RGB);
                String text = recognize(image, ocr);
                elements.addAll(ParserTextSupport.paragraphs(text,
                        "ocr-" + (pageIndex + 1), request.fileName(), pageIndex + 1, ""));
            }
        }
        return elements;
    }

    private String recognize(BufferedImage image, DocumentProcessingProperties.Ocr ocr) throws Exception {
        Path imageFile = Files.createTempFile("xyjai-ocr-", ".png");
        Path outputFile = Files.createTempFile("xyjai-ocr-output-", ".txt");
        Path errorFile = Files.createTempFile("xyjai-ocr-error-", ".txt");
        try {
            ImageIO.write(image, "png", imageFile.toFile());
            ProcessBuilder builder = new ProcessBuilder(
                    ocr.getCommand(), imageFile.toString(), "stdout",
                    "-l", ocr.getLanguages());
            builder.redirectOutput(outputFile.toFile());
            builder.redirectError(errorFile.toFile());
            Process process = builder.start();
            Duration timeout = ocr.getPageTimeout() == null
                    ? Duration.ofSeconds(60) : ocr.getPageTimeout();
            boolean completed = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("OCR处理超时");
            }
            if (process.exitValue() != 0) {
                String error = Files.readString(errorFile, StandardCharsets.UTF_8).trim();
                throw new IllegalStateException("OCR处理失败: " + error);
            }
            return Files.readString(outputFile, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(imageFile);
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(errorFile);
        }
    }
}
