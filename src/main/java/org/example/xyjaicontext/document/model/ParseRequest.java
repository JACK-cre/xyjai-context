package org.example.xyjaicontext.document.model;

public record ParseRequest(String fileName, String mimeType, byte[] content) {
    public ParseRequest {
        fileName = fileName == null || fileName.isBlank() ? "unknown" : fileName;
        mimeType = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
        content = content == null ? new byte[0] : content;
    }
}
