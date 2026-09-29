package org.example.xyjaicontext.document.parser;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.xyjaicontext.config.DocumentProcessingProperties;
import org.example.xyjaicontext.document.model.DocumentElement;
import org.example.xyjaicontext.document.model.DocumentElementType;
import org.example.xyjaicontext.document.model.ParseRequest;
import org.example.xyjaicontext.document.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PdfDocumentParser implements DocumentParser {

    private final List<DocumentOcrService> ocrServices;
    private final DocumentProcessingProperties properties;

    @Override
    public boolean supports(ParseRequest request) {
        return "application/pdf".equals(request.mimeType());
    }

    @Override
    public ParsedDocument parse(ParseRequest request) throws Exception {
        List<DocumentElement> elements = new ArrayList<>();
        int pageCount;
        try (PDDocument document = Loader.loadPDF(request.content())) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("暂不支持加密PDF: " + request.fileName());
            }
            pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                elements.addAll(pageElements(text, request.fileName(), page));
            }
        }

        long visibleChars = elements.stream().map(DocumentElement::text)
                .mapToLong(text -> text.replaceAll("\\s+", "").length()).sum();
        long minimumChars = (long) Math.max(1, pageCount) * properties.getScannedPdfMinCharsPerPage();
        if (visibleChars < minimumChars) {
            for (DocumentOcrService ocrService : ocrServices) {
                if (ocrService.isAvailable()) {
                    List<DocumentElement> ocrElements = ocrService.extract(request, pageCount);
                    if (ocrElements != null && !ocrElements.isEmpty()) {
                        return new ParsedDocument(request.fileName(), request.mimeType(),
                                "pdf-ocr", pageCount, ocrElements);
                    }
                }
            }
            throw new IllegalArgumentException("PDF疑似扫描件，但当前没有可用的OCR实现: " + request.fileName());
        }
        return new ParsedDocument(request.fileName(), request.mimeType(),
                "pdfbox", pageCount, elements);
    }

    @Override
    public int priority() {
        return 100;
    }

    private List<DocumentElement> pageElements(String text, String fileName, int pageNumber) {
        if (text.isBlank()) {
            return List.of();
        }
        List<DocumentElement> elements = new ArrayList<>();
        List<String> paragraphLines = new ArrayList<>();
        List<List<String>> tableRows = new ArrayList<>();
        int sequence = 0;
        for (String rawLine : text.split("\\n")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                sequence = flushParagraph(elements, paragraphLines, fileName, pageNumber, sequence);
                sequence = flushTable(elements, tableRows, fileName, pageNumber, sequence);
                continue;
            }
            List<String> cells = Arrays.stream(line.split("\\s{2,}|\\t+"))
                    .map(ParserTextSupport::normalize).filter(value -> !value.isBlank()).toList();
            if (cells.size() >= 2) {
                sequence = flushParagraph(elements, paragraphLines, fileName, pageNumber, sequence);
                tableRows.add(cells);
            } else {
                sequence = flushTable(elements, tableRows, fileName, pageNumber, sequence);
                paragraphLines.add(ParserTextSupport.normalize(line));
            }
        }
        sequence = flushParagraph(elements, paragraphLines, fileName, pageNumber, sequence);
        flushTable(elements, tableRows, fileName, pageNumber, sequence);
        return elements;
    }

    private int flushParagraph(List<DocumentElement> elements, List<String> lines,
                               String fileName, int pageNumber, int sequence) {
        if (!lines.isEmpty()) {
            elements.add(DocumentElement.text("pdf-" + pageNumber + "-" + sequence++,
                    DocumentElementType.PARAGRAPH, String.join("\n", lines),
                    fileName, pageNumber, ""));
            lines.clear();
        }
        return sequence;
    }

    private int flushTable(List<DocumentElement> elements, List<List<String>> rows,
                           String fileName, int pageNumber, int sequence) {
        if (rows.size() >= 2) {
            String tableId = "pdf-table-" + pageNumber + "-" + sequence;
            elements.add(DocumentElement.table("pdf-" + pageNumber + "-" + sequence++,
                    ParserTextSupport.markdownTable(rows), fileName, pageNumber, "", tableId, rows));
        } else if (rows.size() == 1) {
            elements.add(DocumentElement.text("pdf-" + pageNumber + "-" + sequence++,
                    DocumentElementType.PARAGRAPH, String.join(" ", rows.get(0)),
                    fileName, pageNumber, ""));
        }
        rows.clear();
        return sequence;
    }
}
