package org.example.xyjaicontext.document;

import lombok.RequiredArgsConstructor;
import org.example.xyjaicontext.config.DocumentProcessingProperties;
import org.example.xyjaicontext.document.model.ChunkingResult;
import org.example.xyjaicontext.document.model.DocumentChunk;
import org.example.xyjaicontext.document.model.DocumentElement;
import org.example.xyjaicontext.document.model.DocumentElementType;
import org.example.xyjaicontext.document.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class StructureAwareChunker {

    private final DocumentProcessingProperties properties;
    private final TokenCounter tokenCounter;

    public ChunkingResult chunk(ParsedDocument document) {
        validateConfiguration();
        List<DraftChunk> drafts = createDrafts(document);
        if (drafts.isEmpty()) {
            return new ChunkingResult(List.of(), List.of());
        }
        return buildParentChildChunks(document.fileName(), drafts);
    }

    private List<DraftChunk> createDrafts(ParsedDocument document) {
        List<DraftChunk> drafts = new ArrayList<>();
        TextBuffer buffer = new TextBuffer();
        for (DocumentElement element : document.elements()) {
            if (element.type() == DocumentElementType.TITLE) {
                continue;
            }
            if (element.type() == DocumentElementType.TABLE) {
                flushBuffer(drafts, buffer);
                addTableDrafts(drafts, element);
                continue;
            }
            if (element.text().isBlank()) {
                continue;
            }
            if (!buffer.matches(element)) {
                flushBuffer(drafts, buffer);
            }
            addTextElement(drafts, buffer, element);
        }
        flushBuffer(drafts, buffer);
        mergeTrailingShortChunks(drafts);
        return drafts;
    }

    private void addTextElement(List<DraftChunk> drafts, TextBuffer buffer, DocumentElement element) {
        int maxTokens = properties.getChunking().getMaxChildTokens();
        if (tokenCounter.count(element.text()) > maxTokens) {
            flushBuffer(drafts, buffer);
            for (String part : splitLongText(element.text())) {
                drafts.add(DraftChunk.from(element, part, null, null));
            }
            return;
        }

        String candidate = buffer.isEmpty() ? element.text() : buffer.text() + "\n" + element.text();
        int candidateTokens = tokenCounter.count(candidate);
        int targetTokens = properties.getChunking().getTargetChildTokens();
        int minimumTokens = properties.getChunking().getMinChildTokens();
        if (buffer.isEmpty() || candidateTokens <= targetTokens
                || (buffer.tokenCount(tokenCounter) < minimumTokens && candidateTokens <= maxTokens)) {
            buffer.append(element);
            return;
        }
        flushBuffer(drafts, buffer);
        buffer.append(element);
    }

    private void flushBuffer(List<DraftChunk> drafts, TextBuffer buffer) {
        if (!buffer.isEmpty()) {
            drafts.add(buffer.toDraft());
            buffer.clear();
        }
    }

    private void mergeTrailingShortChunks(List<DraftChunk> drafts) {
        int minimum = properties.getChunking().getMinChildTokens();
        int maximum = properties.getChunking().getMaxChildTokens();
        for (int i = drafts.size() - 1; i > 0; i--) {
            DraftChunk current = drafts.get(i);
            DraftChunk previous = drafts.get(i - 1);
            if (current.type() == DocumentElementType.TABLE
                    || previous.type() == DocumentElementType.TABLE
                    || !previous.sectionKey().equals(current.sectionKey())
                    || tokenCounter.count(current.text()) >= minimum) {
                continue;
            }
            String merged = previous.text() + "\n" + current.text();
            if (tokenCounter.count(merged) <= maximum) {
                drafts.set(i - 1, previous.withText(merged));
                drafts.remove(i);
            }
        }
    }

    private void addTableDrafts(List<DraftChunk> drafts, DocumentElement element) {
        List<List<String>> rows = element.tableRows();
        if (rows.isEmpty()) {
            if (!element.text().isBlank()) {
                drafts.add(DraftChunk.from(element, element.text(), null, null));
            }
            return;
        }
        if (rows.size() == 1) {
            String text = element.text().isBlank()
                    ? "表头: " + String.join(" | ", rows.get(0))
                    : element.text();
            drafts.add(DraftChunk.from(element, text, 1, 1));
            return;
        }
        List<String> headers = rows.get(0);
        int maximumRows = Math.max(1, properties.getChunking().getTableRowsPerChunk());
        int maximumTokens = properties.getChunking().getMaxChildTokens();
        List<String> currentRows = new ArrayList<>();
        int chunkStart = 1;
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            String rowText = renderTableRow(headers, rows.get(rowIndex));
            String singleRowChunk = renderTableChunk(headers, List.of(rowText), null);
            if (tokenCounter.count(singleRowChunk) > maximumTokens) {
                if (!currentRows.isEmpty()) {
                    drafts.add(DraftChunk.from(element,
                            renderTableChunk(headers, currentRows, null), chunkStart + 1, rowIndex));
                    currentRows.clear();
                }
                for (String part : splitOversizedTableRow(headers, rowText, maximumTokens)) {
                    drafts.add(DraftChunk.from(element, part, rowIndex + 1, rowIndex + 1));
                }
                chunkStart = rowIndex + 1;
                continue;
            }
            String candidate = renderTableChunk(headers, currentRows, rowText);
            if (!currentRows.isEmpty()
                    && (currentRows.size() >= maximumRows || tokenCounter.count(candidate) > maximumTokens)) {
                drafts.add(DraftChunk.from(element,
                        renderTableChunk(headers, currentRows, null), chunkStart + 1, rowIndex));
                currentRows.clear();
                chunkStart = rowIndex;
            }
            currentRows.add(rowText);
        }
        if (!currentRows.isEmpty()) {
            drafts.add(DraftChunk.from(element,
                    renderTableChunk(headers, currentRows, null), chunkStart + 1, rows.size()));
        }
    }

    private List<String> splitOversizedTableRow(List<String> headers, String rowText, int maximumTokens) {
        String prefix = "表头: " + String.join(" | ", headers) + "\n数据:\n";
        int available = Math.max(1, maximumTokens - tokenCounter.count(prefix));
        int overlap = Math.min(properties.getChunking().getOverlapTokens(), Math.max(0, available - 1));
        return hardTokenSplit(rowText, available, overlap).stream()
                .map(part -> prefix + part)
                .toList();
    }

    private String renderTableRow(List<String> headers, List<String> row) {
        int columns = Math.max(headers.size(), row.size());
        List<String> values = new ArrayList<>();
        for (int i = 0; i < columns; i++) {
            String header = i < headers.size() && !headers.get(i).isBlank()
                    ? headers.get(i).trim() : "列" + (i + 1);
            String value = i < row.size() ? row.get(i).trim() : "";
            values.add(header + "=" + value);
        }
        return String.join("；", values);
    }

    private String renderTableChunk(List<String> headers, List<String> rows, String additionalRow) {
        List<String> allRows = new ArrayList<>(rows);
        if (additionalRow != null) {
            allRows.add(additionalRow);
        }
        return "表头: " + String.join(" | ", headers) + "\n数据:\n" + String.join("\n", allRows);
    }

    private List<String> splitLongText(String text) {
        int target = properties.getChunking().getTargetChildTokens();
        int maximum = properties.getChunking().getMaxChildTokens();
        int overlap = properties.getChunking().getOverlapTokens();
        List<String> sentences = sentenceUnits(text);
        List<String> result = new ArrayList<>();
        String current = "";
        for (String sentence : sentences) {
            if (tokenCounter.count(sentence) > maximum) {
                if (!current.isBlank()) {
                    result.add(current.trim());
                    current = "";
                }
                result.addAll(hardTokenSplit(sentence, maximum, overlap));
                continue;
            }
            String candidate = current.isBlank() ? sentence : current + sentence;
            if (!current.isBlank() && tokenCounter.count(candidate) > target) {
                result.add(current.trim());
                String tail = tailTokens(current, overlap);
                candidate = tail.isBlank() ? sentence : tail + sentence;
                current = tokenCounter.count(candidate) <= maximum ? candidate : sentence;
            } else {
                current = candidate;
            }
        }
        if (!current.isBlank()) {
            result.add(current.trim());
        }
        return result;
    }

    private List<String> sentenceUnits(String text) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.CHINESE);
        iterator.setText(text);
        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end);
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        return sentences.isEmpty() ? List.of(text) : sentences;
    }

    private List<String> hardTokenSplit(String text, int maximum, int overlap) {
        List<String> result = new ArrayList<>();
        int total = tokenCounter.count(text);
        int step = Math.max(1, maximum - Math.max(0, overlap));
        for (int start = 0; start < total; start += step) {
            String part = tokenCounter.slice(text, start, Math.min(total, start + maximum)).trim();
            if (!part.isBlank()) {
                result.add(part);
            }
            if (start + maximum >= total) {
                break;
            }
        }
        return result;
    }

    private String tailTokens(String text, int tokenCount) {
        if (tokenCount <= 0) {
            return "";
        }
        int total = tokenCounter.count(text);
        return tokenCounter.slice(text, Math.max(0, total - tokenCount), total);
    }

    private ChunkingResult buildParentChildChunks(String fileName, List<DraftChunk> drafts) {
        List<DocumentChunk> parents = new ArrayList<>();
        List<DocumentChunk> children = new ArrayList<>();
        List<DraftChunk> parentDrafts = new ArrayList<>();
        int parentTokens = 0;
        int parentSequence = 0;
        int childSequence = 0;
        for (DraftChunk draft : drafts) {
            int draftTokens = tokenCounter.count(draft.text());
            boolean newSection = !parentDrafts.isEmpty()
                    && !parentDrafts.get(0).sectionKey().equals(draft.sectionKey());
            boolean parentFull = !parentDrafts.isEmpty()
                    && parentTokens + draftTokens > properties.getChunking().getParentMaxTokens();
            if (newSection || parentFull) {
                ParentBuild build = buildParent(fileName, parentDrafts, parentSequence++, childSequence);
                parents.add(build.parent());
                children.addAll(build.children());
                childSequence += build.children().size();
                parentDrafts.clear();
                parentTokens = 0;
            }
            parentDrafts.add(draft);
            parentTokens += draftTokens;
        }
        if (!parentDrafts.isEmpty()) {
            ParentBuild build = buildParent(fileName, parentDrafts, parentSequence, childSequence);
            parents.add(build.parent());
            children.addAll(build.children());
        }
        return new ChunkingResult(parents, children);
    }

    private ParentBuild buildParent(String fileName, List<DraftChunk> drafts,
                                    int parentSequence, int childStart) {
        String parentId = "parent-" + parentSequence;
        DraftChunk first = drafts.get(0);
        String parentText = drafts.stream().map(DraftChunk::text).reduce((left, right) -> left + "\n" + right)
                .orElse("");
        DocumentChunk parent = new DocumentChunk(parentId, "", parentText,
                decorate(fileName, first, parentText), first.titlePath(), first.type(),
                first.pageNumber(), first.sheetName(), first.tableId(), null, null);
        List<DocumentChunk> children = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            DraftChunk draft = drafts.get(i);
            children.add(new DocumentChunk("child-" + (childStart + i), parentId,
                    draft.text(), decorate(fileName, draft, draft.text()), draft.titlePath(),
                    draft.type(), draft.pageNumber(), draft.sheetName(), draft.tableId(),
                    draft.rowStart(), draft.rowEnd()));
        }
        return new ParentBuild(parent, children);
    }

    private String decorate(String fileName, DraftChunk draft, String text) {
        StringBuilder result = new StringBuilder("来源: ").append(fileName);
        if (!draft.titlePath().isBlank()) {
            result.append("\n标题: ").append(draft.titlePath());
        }
        if (draft.pageNumber() != null) {
            result.append("\n页码: ").append(draft.pageNumber());
        }
        if (!draft.sheetName().isBlank()) {
            result.append("\n工作表: ").append(draft.sheetName());
        }
        result.append("\n内容:\n").append(text);
        return result.toString();
    }

    private void validateConfiguration() {
        DocumentProcessingProperties.Chunking chunking = properties.getChunking();
        if (chunking.getMinChildTokens() < 1
                || chunking.getTargetChildTokens() < chunking.getMinChildTokens()
                || chunking.getMaxChildTokens() < chunking.getTargetChildTokens()
                || chunking.getParentMaxTokens() < chunking.getMaxChildTokens()
                || chunking.getOverlapTokens() < 0
                || chunking.getOverlapTokens() >= chunking.getMaxChildTokens()) {
            throw new IllegalArgumentException("文档切片参数非法");
        }
    }

    private record ParentBuild(DocumentChunk parent, List<DocumentChunk> children) {
    }

    private record DraftChunk(String text, String titlePath, DocumentElementType type,
                              Integer pageNumber, String sheetName, String tableId,
                              Integer rowStart, Integer rowEnd) {
        static DraftChunk from(DocumentElement element, String text, Integer rowStart, Integer rowEnd) {
            return new DraftChunk(text, element.titlePath(), element.type(), element.pageNumber(),
                    element.sheetName(), element.tableId(), rowStart, rowEnd);
        }

        String sectionKey() {
            return titlePath + "|" + sheetName + "|" + tableId;
        }

        DraftChunk withText(String value) {
            return new DraftChunk(value, titlePath, type, pageNumber, sheetName,
                    tableId, rowStart, rowEnd);
        }
    }

    private static final class TextBuffer {
        private final List<DocumentElement> elements = new ArrayList<>();

        boolean isEmpty() {
            return elements.isEmpty();
        }

        boolean matches(DocumentElement element) {
            if (isEmpty()) {
                return true;
            }
            DocumentElement first = elements.get(0);
            return Objects.equals(first.titlePath(), element.titlePath())
                    && Objects.equals(first.sheetName(), element.sheetName());
        }

        void append(DocumentElement element) {
            elements.add(element);
        }

        String text() {
            return elements.stream().map(DocumentElement::text)
                    .reduce((left, right) -> left + "\n" + right).orElse("");
        }

        int tokenCount(TokenCounter counter) {
            return counter.count(text());
        }

        DraftChunk toDraft() {
            DocumentElement first = elements.get(0);
            DocumentElementType type = elements.stream().map(DocumentElement::type).distinct().count() == 1
                    ? first.type() : DocumentElementType.PARAGRAPH;
            return new DraftChunk(text(), first.titlePath(), type, first.pageNumber(),
                    first.sheetName(), "", null, null);
        }

        void clear() {
            elements.clear();
        }
    }
}
