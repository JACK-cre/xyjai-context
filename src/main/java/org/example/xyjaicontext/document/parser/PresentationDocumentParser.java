package org.example.xyjaicontext.document.parser;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.example.xyjaicontext.document.model.DocumentElement;
import org.example.xyjaicontext.document.model.DocumentElementType;
import org.example.xyjaicontext.document.model.ParseRequest;
import org.example.xyjaicontext.document.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class PresentationDocumentParser implements DocumentParser {

    private static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    @Override
    public boolean supports(ParseRequest request) {
        return PPTX_MIME.equals(request.mimeType());
    }

    @Override
    public ParsedDocument parse(ParseRequest request) throws Exception {
        List<DocumentElement> elements = new ArrayList<>();
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(request.content()))) {
            int sequence = 0;
            for (XSLFSlide slide : slideShow.getSlides()) {
                int pageNumber = slide.getSlideNumber();
                String title = ParserTextSupport.normalize(slide.getTitle());
                String titlePath = title.isBlank()
                        ? request.fileName() + " > 第" + pageNumber + "页"
                        : request.fileName() + " > " + title;
                if (!title.isBlank()) {
                    elements.add(DocumentElement.text("ppt-" + sequence++,
                            DocumentElementType.TITLE, title, titlePath, pageNumber, ""));
                }
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTable table) {
                        List<List<String>> rows = tableRows(table);
                        if (!rows.isEmpty()) {
                            String tableId = "ppt-table-" + pageNumber + "-" + sequence;
                            elements.add(DocumentElement.table("ppt-" + sequence++,
                                    ParserTextSupport.markdownTable(rows), titlePath,
                                    pageNumber, "", tableId, rows));
                        }
                    } else if (shape instanceof XSLFTextShape textShape) {
                        String text = ParserTextSupport.normalize(textShape.getText());
                        if (!text.isBlank() && !text.equals(title)) {
                            elements.add(DocumentElement.text("ppt-" + sequence++,
                                    DocumentElementType.PARAGRAPH, text, titlePath, pageNumber, ""));
                        }
                    }
                }
                sequence = addNotes(elements, slide.getNotes(), titlePath, pageNumber, sequence);
            }
            return new ParsedDocument(request.fileName(), request.mimeType(),
                    "apache-poi-presentation", slideShow.getSlides().size(), elements);
        }
    }

    @Override
    public int priority() {
        return 100;
    }

    private int addNotes(List<DocumentElement> elements, XSLFNotes notes,
                         String titlePath, int pageNumber, int sequence) {
        if (notes == null) {
            return sequence;
        }
        for (XSLFShape shape : notes.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                String text = ParserTextSupport.normalize(textShape.getText());
                if (!text.isBlank()) {
                    elements.add(DocumentElement.text("ppt-" + sequence++,
                            DocumentElementType.PARAGRAPH, "备注: " + text,
                            titlePath, pageNumber, ""));
                }
            }
        }
        return sequence;
    }

    private List<List<String>> tableRows(XSLFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XSLFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XSLFTableCell cell : row.getCells()) {
                cells.add(ParserTextSupport.normalize(cell.getText()));
            }
            if (cells.stream().anyMatch(value -> !value.isBlank())) {
                rows.add(cells);
            }
        }
        return rows;
    }
}
