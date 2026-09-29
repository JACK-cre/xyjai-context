package org.example.xyjaicontext.document.model;

public record DocumentChunk(
        String chunkId,
        String parentId,
        String text,
        String indexText,
        String titlePath,
        DocumentElementType elementType,
        Integer pageNumber,
        String sheetName,
        String tableId,
        Integer rowStart,
        Integer rowEnd) {

    public DocumentChunk {
        parentId = parentId == null ? "" : parentId;
        text = text == null ? "" : text.trim();
        indexText = indexText == null ? text : indexText.trim();
        titlePath = titlePath == null ? "" : titlePath.trim();
        elementType = elementType == null ? DocumentElementType.PARAGRAPH : elementType;
        sheetName = sheetName == null ? "" : sheetName.trim();
        tableId = tableId == null ? "" : tableId.trim();
    }

    public DocumentChunk withParentId(String newParentId) {
        return new DocumentChunk(chunkId, newParentId, text, indexText, titlePath,
                elementType, pageNumber, sheetName, tableId, rowStart, rowEnd);
    }

    public DocumentChunk withText(String newText, String newIndexText) {
        return new DocumentChunk(chunkId, parentId, newText, newIndexText, titlePath,
                elementType, pageNumber, sheetName, tableId, rowStart, rowEnd);
    }
}
