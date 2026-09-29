package org.example.xyjaicontext.document;

import lombok.RequiredArgsConstructor;
import org.example.xyjaicontext.document.model.ChunkingResult;
import org.example.xyjaicontext.document.model.DocumentProcessingResult;
import org.example.xyjaicontext.document.model.ParseRequest;
import org.example.xyjaicontext.document.model.ParsedDocument;
import org.example.xyjaicontext.document.parser.DocumentParserRegistry;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final DocumentFileValidator fileValidator;
    private final DocumentParserRegistry parserRegistry;
    private final StructureAwareChunker chunker;

    public DocumentProcessingResult process(String originalFileName, byte[] content) {
        DetectedDocumentType detected = fileValidator.validate(originalFileName, content);
        ParseRequest request = new ParseRequest(
                detected.fileName(), detected.mimeType(), content);
        ParsedDocument parsed = parserRegistry.parse(request);
        ChunkingResult chunking = chunker.chunk(parsed);
        if (chunking.children().isEmpty()) {
            throw new IllegalArgumentException("文档未生成可索引的内容块: " + detected.fileName());
        }
        return new DocumentProcessingResult(parsed, chunking);
    }
}
