package org.example.xyjaicontext.document.parser;

import org.example.xyjaicontext.document.model.DocumentElement;
import org.example.xyjaicontext.document.model.DocumentElementType;

import java.util.ArrayList;
import java.util.List;

final class ParserTextSupport {

    private ParserTextSupport() {
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u0000', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("[ ]{2,}", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    static List<DocumentElement> paragraphs(String text, String idPrefix, String titlePath,
                                            Integer pageNumber, String sheetName) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] blocks = normalized.split("\\n\\s*\\n");
        List<DocumentElement> elements = new ArrayList<>();
        int index = 0;
        for (String block : blocks) {
            String value = block.trim();
            if (!value.isBlank()) {
                elements.add(DocumentElement.text(idPrefix + "-" + index++,
                        DocumentElementType.PARAGRAPH, value, titlePath, pageNumber, sheetName));
            }
        }
        return elements;
    }

    static String markdownTable(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) {
            return "";
        }
        List<String> header = normalizedRow(rows.get(0), columns);
        StringBuilder result = new StringBuilder();
        result.append("| ").append(String.join(" | ", header)).append(" |\n");
        result.append("| ").append("--- | ".repeat(Math.max(0, columns - 1))).append("--- |\n");
        for (int i = 1; i < rows.size(); i++) {
            result.append("| ").append(String.join(" | ", normalizedRow(rows.get(i), columns))).append(" |\n");
        }
        return result.toString().trim();
    }

    static List<String> normalizedRow(List<String> row, int columns) {
        List<String> normalized = new ArrayList<>(columns);
        for (int i = 0; i < columns; i++) {
            String value = i < row.size() && row.get(i) != null ? row.get(i) : "";
            normalized.add(normalize(value).replace("|", "\\|").replace('\n', ' '));
        }
        return normalized;
    }
}
