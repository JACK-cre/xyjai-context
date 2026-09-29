package org.example.xyjaicontext.document.parser;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.example.xyjaicontext.document.model.DocumentElementType;
import org.example.xyjaicontext.document.model.ParseRequest;
import org.example.xyjaicontext.document.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class OfficeDocumentParserTest {

    @Test
    void wordParserPreservesHeadingParagraphAndTableStructure() throws Exception {
        byte[] content;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("退款规则");
            document.createParagraph().createRun().setText("普通退款为3个工作日。");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("订单类型");
            table.getRow(0).getCell(1).setText("退款时间");
            table.getRow(1).getCell(0).setText("跨境订单");
            table.getRow(1).getCell(1).setText("15天");
            document.write(output);
            content = output.toByteArray();
        }

        ParsedDocument parsed = new WordDocumentParser().parse(new ParseRequest(
                "rules.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                content));

        assertThat(parsed.parserName()).isEqualTo("apache-poi-word");
        assertThat(parsed.elements()).extracting(element -> element.type())
                .contains(DocumentElementType.TITLE, DocumentElementType.PARAGRAPH, DocumentElementType.TABLE);
        assertThat(parsed.elements()).filteredOn(element -> element.type() == DocumentElementType.PARAGRAPH)
                .allSatisfy(element -> assertThat(element.titlePath()).isEqualTo("退款规则"));
        assertThat(parsed.elements()).filteredOn(element -> element.type() == DocumentElementType.TABLE)
                .singleElement().satisfies(element -> {
                    assertThat(element.tableRows()).hasSize(2);
                    assertThat(element.text()).contains("订单类型", "跨境订单", "15天");
                });
    }

    @Test
    void spreadsheetParserPreservesSheetHeaderAndFormulaValue() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("华东区");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("产品");
            header.createCell(1).setCellValue("金额");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("A");
            data.createCell(1).setCellFormula("2*2500");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(output);
            content = output.toByteArray();
        }

        ParsedDocument parsed = new SpreadsheetDocumentParser().parse(new ParseRequest(
                "sales.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content));

        assertThat(parsed.elements()).singleElement().satisfies(element -> {
            assertThat(element.type()).isEqualTo(DocumentElementType.TABLE);
            assertThat(element.sheetName()).isEqualTo("华东区");
            assertThat(element.titlePath()).isEqualTo("sales.xlsx > 华东区");
            assertThat(element.text()).contains("产品", "金额", "A", "5000");
        });
    }

    @Test
    void presentationParserPreservesSlideTextAndTables() throws Exception {
        byte[] content;
        try (XMLSlideShow slideShow = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var slide = slideShow.createSlide();
            slide.createTextBox().setText("季度退款分析");
            var table = slide.createTable(2, 2);
            table.getCell(0, 0).setText("地区");
            table.getCell(0, 1).setText("金额");
            table.getCell(1, 0).setText("华东");
            table.getCell(1, 1).setText("5000");
            slideShow.write(output);
            content = output.toByteArray();
        }

        ParsedDocument parsed = new PresentationDocumentParser().parse(new ParseRequest(
                "report.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                content));

        assertThat(parsed.pageCount()).isEqualTo(1);
        assertThat(parsed.elements()).extracting(element -> element.type())
                .contains(DocumentElementType.PARAGRAPH, DocumentElementType.TABLE);
        assertThat(parsed.elements()).filteredOn(element -> element.type() == DocumentElementType.TABLE)
                .singleElement().satisfies(element ->
                        assertThat(element.text()).contains("地区", "华东", "5000"));
    }
}
