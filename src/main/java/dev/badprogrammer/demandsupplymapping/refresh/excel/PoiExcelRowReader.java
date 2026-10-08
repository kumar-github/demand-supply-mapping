package dev.badprogrammer.demandsupplymapping.refresh.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The original reader: Apache POI's DOM-based {@code XSSFWorkbook}. This is the exact logic that used to live in
 * {@code ExcelSheetReader} directly (moved here unchanged, 2026-10-02, when {@link FastExcelRowReader} was added
 * alongside it — see {@link ExcelRowReaders} for how the two are switched between).
 * <p>
 * Reads the first sheet of an .xlsx file the way pandas.read_excel() effectively does for this pipeline's purposes: row
 * 0 is the header, every cell is rendered to a String (numbers without a trailing ".0" unless they're genuinely
 * fractional, so "12345" round-trips the same way Employee Code / Job Requisition ID do in the Python CSVs), and a row
 * is skipped only if every cell in it is blank.
 * <p>
 * Deliberately does NOT replicate pandas' NaN semantics exactly — a missing/blank cell always comes back as "" here,
 * and callers (see RefreshLogic.nz()) treat "" and null as equivalent, which is what every filter in classify_inputs.py
 * actually checks for.
 * <p>
 * Known memory characteristic: {@code XSSFWorkbook} loads the entire workbook as an in-memory XML object tree (via
 * xmlbeans) before a single cell can be read — this is what caused the real OutOfMemoryError on Render's free 512MB
 * tier (see DEPLOY.md, "Known fix #2"). {@link FastExcelRowReader} exists specifically to avoid this.
 */
public final class PoiExcelRowReader implements ExcelRowReader {

    private static final DecimalFormat WHOLE_NUMBER = new DecimalFormat("#");

    private static String cellToString(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double v = cell.getNumericCellValue();
            if (v == Math.floor(v) && !Double.isInfinite(v)) {
                return WHOLE_NUMBER.format(v);
            }
            return Double.toString(v);
        }
        if (cell.getCellType() == CellType.FORMULA) {
            // Evaluated formula result rendered the same way as a plain cell.
            return formatter.formatCellValue(cell);
        }
        return formatter.formatCellValue(cell);
    }

    @Override
    public List<Map<String, String>> readFirstSheet(Path xlsxPath) throws IOException {
        try (InputStream in = new FileInputStream(xlsxPath.toFile());
             Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();

            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new IOException("Sheet has no header row: " + xlsxPath);
            }
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(cellToString(cell, formatter).trim());
            }

            List<Map<String, String>> rows = new ArrayList<>();
            for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> record = new LinkedHashMap<>();
                boolean allBlank = true;
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String value = cell == null ? "" : cellToString(cell, formatter).trim();
                    if (!value.isEmpty()) {
                        allBlank = false;
                    }
                    record.put(headers.get(c), value);
                }
                if (!allBlank) {
                    rows.add(record);
                }
            }
            return rows;
        }
    }
}
