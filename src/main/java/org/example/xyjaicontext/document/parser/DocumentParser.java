package org.example.xyjaicontext.document.parser;

import org.example.xyjaicontext.document.model.ParseRequest;
import org.example.xyjaicontext.document.model.ParsedDocument;

public interface DocumentParser {

    boolean supports(ParseRequest request);

    ParsedDocument parse(ParseRequest request) throws Exception;

    default int priority() {
        return 0;
    }
}
