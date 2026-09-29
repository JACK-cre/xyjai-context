package org.example.xyjaicontext.document.model;

import java.util.List;
import java.util.Map;

public record DocumentElement(
        String id,
        DocumentElementType type,
        String text,
        String titlePath,
        Integer pageNumber,
        String sheetName,
        String tableId,
        List<List<String>> tableRows,
        Map<String, Object> metadata) {

    public DocumentElement {
        type = type == null ? DocumentElementType.PARAGRAPH : type;
        text = text == null ? "" : text.trim();
        titlePath = titlePath == null ? "" : titlePath.trim();
        sheetName = sheetName == null ? "" : sheetName.trim();
        tableId = tableId == null ? "" : tableId.trim();
        tableRows = tableRows == null ? List.of() : tableRows.stream().map(List::copyOf).toList();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static DocumentElement text(String id, DocumentElementType type, String text,
                                       String titlePath, Integer pageNumber, String sheetName) {
        return new DocumentElement(id, type, text, titlePath, pageNumber, sheetName,
                "", List.of(), Map.of());
    }

    public static DocumentElement table(String id, String text, String titlePath,
                                        Integer pageNumber, String sheetName,
                                        String tableId, List<List<String>> rows) {
        return new DocumentElement(id, DocumentElementType.TABLE, text, titlePath,
                pageNumber, sheetName, tableId, rows, Map.of());
    }
}
