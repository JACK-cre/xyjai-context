package org.example.xyjaicontext.document.parser;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.example.xyjaicontext.document.model.DocumentElement;
import org.example.xyjaicontext.document.model.DocumentElementType;
import org.example.xyjaicontext.document.model.ParseRequest;
import org.example.xyjaicontext.document.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class WordDocumentParser implements DocumentParser {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Override
    public boolean supports(ParseRequest request) {
        return DOCX_MIME.equals(request.mimeType());
    }

    @Override
    public ParsedDocument parse(ParseRequest request) throws Exception {
        List<DocumentElement> elements = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        int sequence = 0;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(request.content()))) {
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (bodyElement instanceof XWPFParagraph paragraph) {
                    String text = ParserTextSupport.normalize(paragraph.getText());
                    if (text.isBlank()) {
                        continue;
                    }
                    int headingLevel = headingLevel(document, paragraph);
                    if (headingLevel > 0) {
                        updateHeadings(headings, headingLevel, text);
                        elements.add(DocumentElement.text("word-" + sequence++,
                                DocumentElementType.TITLE, text, titlePath(headings), null, ""));
                    } else {
                        DocumentElementType type = paragraph.getNumID() == null
                                ? DocumentElementType.PARAGRAPH : DocumentElementType.LIST;
                        elements.add(DocumentElement.text("word-" + sequence++, type, text,
                                titlePath(headings, request.fileName()), null, ""));
                    }
                } else if (bodyElement instanceof XWPFTable table) {
                    List<List<String>> rows = tableRows(table);
                    if (!rows.isEmpty()) {
                        String tableId = "word-table-" + sequence;
                        elements.add(DocumentElement.table("word-" + sequence++,
                                ParserTextSupport.markdownTable(rows),
                                titlePath(headings, request.fileName()), null, "", tableId, rows));
                    }
                }
            }
        }
        return new ParsedDocument(request.fileName(), request.mimeType(),
                "apache-poi-word", 0, elements);
    }

    @Override
    public int priority() {
        return 100;
    }

    private int headingLevel(XWPFDocument document, XWPFParagraph paragraph) {
        String styleId = paragraph.getStyle();
        String styleName = styleId;
        if (styleId != null && document.getStyles() != null) {
            XWPFStyle style = document.getStyles().getStyle(styleId);
            if (style != null && style.getName() != null) {
                styleName = style.getName();
            }
        }
        if (styleName == null) {
            return 0;
        }
        String normalized = styleName.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (int level = 1; level <= 6; level++) {
            if (normalized.equals("heading" + level) || normalized.equals("标题" + level)) {
                return level;
            }
        }
        return 0;
    }

    private void updateHeadings(List<String> headings, int level, String text) {
        while (headings.size() >= level) {
            headings.remove(headings.size() - 1);
        }
        while (headings.size() < level - 1) {
            headings.add("");
        }
        headings.add(text);
    }

    private String titlePath(List<String> headings) {
        return headings.stream().filter(value -> !value.isBlank()).reduce((left, right) -> left + " > " + right)
                .orElse("");
    }

    private String titlePath(List<String> headings, String fallback) {
        String value = titlePath(headings);
        return value.isBlank() ? fallback : value;
    }

    private List<List<String>> tableRows(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String text = cell.getParagraphs().stream().map(XWPFParagraph::getText)
                        .map(ParserTextSupport::normalize).filter(value -> !value.isBlank())
                        .reduce((left, right) -> left + " / " + right).orElse("");
                cells.add(text);
            }
            if (cells.stream().anyMatch(value -> !value.isBlank())) {
                rows.add(cells);
            }
        }
        return rows;
    }
}
