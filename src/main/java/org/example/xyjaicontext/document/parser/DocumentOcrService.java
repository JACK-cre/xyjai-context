package org.example.xyjaicontext.document.parser;

import org.example.xyjaicontext.document.model.DocumentElement;
import org.example.xyjaicontext.document.model.ParseRequest;

import java.util.List;

/** Extension point for a local or remote OCR engine used by scanned PDFs. */
public interface DocumentOcrService {

    boolean isAvailable();

    List<DocumentElement> extract(ParseRequest request, int pageCount) throws Exception;
}
