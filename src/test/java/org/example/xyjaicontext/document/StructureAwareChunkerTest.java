package org.example.xyjaicontext.document;

import org.example.xyjaicontext.config.DocumentProcessingProperties;
import org.example.xyjaicontext.document.model.ChunkingResult;
import org.example.xyjaicontext.document.model.DocumentElement;
import org.example.xyjaicontext.document.model.DocumentElementType;
import org.example.xyjaicontext.document.model.ParsedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructureAwareChunkerTest {

    private DocumentProcessingProperties properties;
    private TokenCounter tokenCounter;
    private StructureAwareChunker chunker;

    @BeforeEach
    void setUp() {
        properties = new DocumentProcessingProperties();
        properties.getChunking().setMinChildTokens(10);
        properties.getChunking().setTargetChildTokens(25);
        properties.getChunking().setMaxChildTokens(45);
        properties.getChunking().setParentMaxTokens(90);
        properties.getChunking().setOverlapTokens(5);
        properties.getChunking().setTableRowsPerChunk(2);
        tokenCounter = new TokenCounter();
        chunker = new StructureAwareChunker(properties, tokenCounter);
    }

    @Test
    void mergesShortParagraphsInsideTheSameSectionOnly() {
        ParsedDocument document = parsed(List.of(
                text("1", "普通退款为三个工作日。", "退款规则"),
                text("2", "跨境订单除外。", "退款规则"),
                text("3", "试用期为三个月。", "员工制度")));

        ChunkingResult result = chunker.chunk(document);

        assertThat(result.children()).hasSize(2);
        assertThat(result.children().get(0).text())
                .contains("普通退款为三个工作日", "跨境订单除外");
        assertThat(result.children().get(1).text()).contains("试用期为三个月");
        assertThat(result.children().get(0).parentId())
                .isNotEqualTo(result.children().get(1).parentId());
    }

    @Test
    void repeatsTableHeaderAndKeepsSourceRowRanges() {
        List<List<String>> rows = List.of(
                List.of("订单类型", "退款时间"),
                List.of("普通订单", "3天"),
                List.of("跨境订单", "15天"),
                List.of("预售订单", "7天"));
        DocumentElement table = DocumentElement.table("table-1", "", "售后 > 退款",
                2, "", "refund-table", rows);

        ChunkingResult result = chunker.chunk(parsed(List.of(table)));

        assertThat(result.children()).hasSize(2);
        assertThat(result.children()).allSatisfy(chunk ->
                assertThat(chunk.text()).startsWith("表头: 订单类型 | 退款时间"));
        assertThat(result.children().get(0).rowStart()).isEqualTo(2);
        assertThat(result.children().get(0).rowEnd()).isEqualTo(3);
        assertThat(result.children().get(1).rowStart()).isEqualTo(4);
        assertThat(result.children().get(1).rowEnd()).isEqualTo(4);
    }

    @Test
    void splitsOversizedTableRowsWithoutDroppingTheHeader() {
        List<List<String>> rows = List.of(
                List.of("规则", "说明"),
                List.of("超长规则", "这是一个很长的表格单元格内容。".repeat(30)));
        DocumentElement table = DocumentElement.table("table-1", "", "规则说明",
                3, "", "long-table", rows);

        ChunkingResult result = chunker.chunk(parsed(List.of(table)));

        assertThat(result.children()).hasSizeGreaterThan(1);
        assertThat(result.children()).allSatisfy(chunk -> {
            assertThat(chunk.text()).startsWith("表头: 规则 | 说明");
            assertThat(tokenCounter.count(chunk.text())).isLessThanOrEqualTo(45);
            assertThat(chunk.rowStart()).isEqualTo(2);
            assertThat(chunk.rowEnd()).isEqualTo(2);
        });
    }

    @Test
    void splitsLongChineseTextWithinConfiguredMaximum() {
        String sentence = "这是一个用于验证中文长段落切分边界的完整句子。";
        ParsedDocument document = parsed(List.of(text("1", sentence.repeat(30), "测试章节")));

        ChunkingResult result = chunker.chunk(document);

        assertThat(result.children()).hasSizeGreaterThan(1);
        assertThat(result.children()).allSatisfy(chunk ->
                assertThat(tokenCounter.count(chunk.text())).isLessThanOrEqualTo(45));
        assertThat(result.children()).allSatisfy(chunk ->
                assertThat(chunk.indexText()).contains("来源: test.docx", "标题: 测试章节"));
    }

    private ParsedDocument parsed(List<DocumentElement> elements) {
        return new ParsedDocument("test.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "test", 1, elements);
    }

    private DocumentElement text(String id, String value, String titlePath) {
        return DocumentElement.text(id, DocumentElementType.PARAGRAPH,
                value, titlePath, 1, "");
    }
}
