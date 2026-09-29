package org.example.xyjaicontext.document.model;

import java.util.List;

public record ParsedDocument(
        String fileName,
        String mimeType,
        String parserName,
        int pageCount,
        List<DocumentElement> elements) {

    public ParsedDocument {
        fileName = fileName == null ? "unknown" : fileName;
        mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        parserName = parserName == null ? "unknown" : parserName;
        pageCount = Math.max(0, pageCount);
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    public List<String> searchableText() {
        return elements.stream()
                .filter(element -> element.type() != DocumentElementType.TITLE)
                .map(DocumentElement::text)
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }
}
