package org.example.xyjaicontext.document.parser;

import org.example.xyjaicontext.document.model.DocumentElement;
import org.example.xyjaicontext.document.model.ParseRequest;
import org.example.xyjaicontext.document.model.ParsedDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TikaFallbackDocumentParser implements DocumentParser {

    @Override
    public boolean supports(ParseRequest request) {
        return true;
    }

    @Override
    public ParsedDocument parse(ParseRequest request) {
        ByteArrayResource resource = new ByteArrayResource(request.content()) {
            @Override
            public String getFilename() {
                return request.fileName();
            }
        };
        List<Document> documents = new TikaDocumentReader(resource).get();
        List<DocumentElement> elements = new ArrayList<>();
        int index = 0;
        for (Document document : documents) {
            elements.addAll(ParserTextSupport.paragraphs(document.getText(),
                    "tika-" + index++, request.fileName(), null, ""));
        }
        return new ParsedDocument(request.fileName(), request.mimeType(),
                "tika", 0, elements);
    }

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }
}
