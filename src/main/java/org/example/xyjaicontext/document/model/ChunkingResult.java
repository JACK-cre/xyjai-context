package org.example.xyjaicontext.document.model;

import java.util.List;

public record ChunkingResult(List<DocumentChunk> parents, List<DocumentChunk> children) {
    public ChunkingResult {
        parents = parents == null ? List.of() : List.copyOf(parents);
        children = children == null ? List.of() : List.copyOf(children);
    }
}
