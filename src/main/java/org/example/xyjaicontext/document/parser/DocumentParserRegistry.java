package org.example.xyjaicontext.document.parser;

import lombok.RequiredArgsConstructor;
import org.example.xyjaicontext.document.model.ParseRequest;
import org.example.xyjaicontext.document.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DocumentParserRegistry {

    private final List<DocumentParser> parsers;

    public ParsedDocument parse(ParseRequest request) {
        DocumentParser parser = parsers.stream()
                .filter(candidate -> candidate.supports(request))
                .max(Comparator.comparingInt(DocumentParser::priority))
                .orElseThrow(() -> new IllegalArgumentException("没有可用的文档解析器"));
        try {
            ParsedDocument parsed = parser.parse(request);
            if (parsed.elements().isEmpty()) {
                throw new IllegalArgumentException("文档未解析出有效内容: " + request.fileName());
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("文档解析失败: " + request.fileName(), e);
        }
    }
}
