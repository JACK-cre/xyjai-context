package org.example.xyjaicontext.document.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.xyjaicontext.document.model.DocumentElement;
import org.example.xyjaicontext.document.model.ParseRequest;
import org.example.xyjaicontext.document.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class SpreadsheetDocumentParser implements DocumentParser {

    private static final Set<String> MIME_TYPES = Set.of(
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    @Override
    public boolean supports(ParseRequest request) {
        return MIME_TYPES.contains(request.mimeType());
    }

    @Override
    public ParsedDocument parse(ParseRequest request) throws Exception {
        List<DocumentElement> elements = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(request.content()))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            int tableSequence = 0;
            for (Sheet sheet : workbook) {
                List<List<String>> rows = readRows(sheet, formatter, evaluator);
                if (rows.isEmpty()) {
                    continue;
                }
                String titlePath = request.fileName() + " > " + sheet.getSheetName();
                String tableId = "sheet-table-" + tableSequence;
                elements.add(DocumentElement.table("sheet-" + tableSequence++,
                        ParserTextSupport.markdownTable(rows), titlePath, null,
                        sheet.getSheetName(), tableId, rows));
            }
            return new ParsedDocument(request.fileName(), request.mimeType(),
                    "apache-poi-spreadsheet", workbook.getNumberOfSheets(), elements);
        }
    }

    @Override
    public int priority() {
        return 100;
    }

    private List<List<String>> readRows(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<List<String>> rows = new ArrayList<>();
        int maximumColumns = 0;
        for (Row row : sheet) {
            maximumColumns = Math.max(maximumColumns, Math.max(0, row.getLastCellNum()));
        }
        if (maximumColumns == 0) {
            return rows;
        }
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            List<String> cells = new ArrayList<>(maximumColumns);
            for (int column = 0; column < maximumColumns; column++) {
                Cell cell = row == null ? null : row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                cells.add(formatCell(cell, formatter, evaluator));
            }
            while (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) {
                cells.remove(cells.size() - 1);
            }
            if (cells.stream().anyMatch(value -> !value.isBlank())) {
                rows.add(cells);
            }
        }
        return rows;
    }

    private String formatCell(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        try {
            return ParserTextSupport.normalize(formatter.formatCellValue(cell, evaluator));
        } catch (RuntimeException ignored) {
            return ParserTextSupport.normalize(formatter.formatCellValue(cell));
        }
    }
}
