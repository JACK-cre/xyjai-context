package org.example.xyjaicontext.document.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.example.xyjaicontext.config.DocumentProcessingProperties;
import org.example.xyjaicontext.document.model.ParseRequest;
import org.example.xyjaicontext.document.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDocumentParserTest {

    @Test
    void extractsTextPerPage() throws Exception {
        byte[] content = pdfWithText("Refunds are completed within three working days.");
        DocumentProcessingProperties properties = new DocumentProcessingProperties();
        PdfDocumentParser parser = new PdfDocumentParser(List.of(), properties);

        ParsedDocument parsed = parser.parse(new ParseRequest("rules.pdf", "application/pdf", content));

        assertThat(parsed.pageCount()).isEqualTo(1);
        assertThat(parsed.parserName()).isEqualTo("pdfbox");
        assertThat(parsed.elements()).isNotEmpty().allSatisfy(element ->
                assertThat(element.pageNumber()).isEqualTo(1));
        assertThat(parsed.searchableText()).anySatisfy(text ->
                assertThat(text).contains("three working days"));
    }

    @Test
    void reportsMissingOcrForScannedPdf() throws Exception {
        byte[] content;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            content = output.toByteArray();
        }
        PdfDocumentParser parser = new PdfDocumentParser(List.of(), new DocumentProcessingProperties());

        assertThatThrownBy(() -> parser.parse(new ParseRequest("scan.pdf", "application/pdf", content)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OCR");
    }

    @Test
    void recognizesSimpleWhitespaceAlignedTables() throws Exception {
        byte[] content = pdfWithText("Product        Amount\nA              5000");
        PdfDocumentParser parser = new PdfDocumentParser(List.of(), new DocumentProcessingProperties());

        ParsedDocument parsed = parser.parse(new ParseRequest("table.pdf", "application/pdf", content));

        assertThat(parsed.elements()).anySatisfy(element -> {
            assertThat(element.type().name()).isEqualTo("TABLE");
            assertThat(element.tableRows()).containsExactly(
                    List.of("Product", "Amount"), List.of("A", "5000"));
        });
    }

    private byte[] pdfWithText(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                String[] lines = text.split("\\n");
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) {
                        stream.newLineAtOffset(0, -18);
                    }
                    stream.showText(lines[i]);
                }
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
