package dev.badprogrammer.demandsupplymapping.refresh.excel;

import org.dhatim.fastexcel.reader.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Low-memory replacement for {@link PoiExcelRowReader}, added 2026-10-02 after a real OutOfMemoryError on Render's free
 * tier (POI's DOM-based {@code XSSFWorkbook} loading the whole workbook as an in-memory XML tree — see DEPLOY.md,
 * "Known fix #2"). Uses {@code org.dhatim:fastexcel-reader}, which streams rows instead of building that tree. See
 * {@link ExcelRowReaders} for how this is switched on, and {@code ExcelReaderComparison} (in src/test) for the tool
 * used to verify this reader produces the same output as {@link PoiExcelRowReader} on your real workbooks before
 * relying on it in production.
 * <p>
 * <b>Verified against the real project files before this was written</b> (2026-10-02, via openpyxl against
 * Demand.xlsx / AFD-Supply.xlsx / AAFD-Supply.xlsx): zero formula cells and zero genuine date-typed cells in any of
 * them. That matters because it's exactly where this implementation's fidelity to {@link PoiExcelRowReader} is weakest
 * — see the two caveats below. For the data this project actually has today, those paths should never be exercised. If
 * a future source file *does* carry formulas or real Excel date cells, re-run the comparison tool against it before
 * trusting this reader on it.
 * <ul>
 *   <li><b>Formula cells (untested, no coverage in real data):</b> FastExcel's reader (confirmed from its own
 *       {@code Cell.java} source) exposes a formula cell's cached numeric result via {@code getRawValue()}
 *       (the same field its own {@code asDate()} reads for formula cells — {@code asNumber()} itself only works
 *       for {@code CellType.NUMBER}, not {@code FORMULA}), but it does not apply the cell's number-format style
 *       the way POI's {@code DataFormatter} does — so a
 *       currency- or percentage-formatted formula cell would render here as a plain number
 *       (e.g. "1234" instead of "$1,234.00"), where {@link PoiExcelRowReader} would apply the style. This is a
 *       real behavioral difference from the original reader, not something this code works around.</li>
 *   <li><b>Error cells (untested, no coverage in real data):</b> rendered via {@link Cell#getText()} as a
 *       best effort; not verified against what POI's {@code DataFormatter} would produce for the same cell.</li>
 * </ul>
 * Everything else below was deliberately written to match {@link PoiExcelRowReader} as closely as this library's
 * API allows:
 * <ul>
 *   <li>Numeric cells are rendered through the exact same {@code WHOLE_NUMBER} / {@code Double.toString} logic as
 *       the POI reader (just fed from {@code BigDecimal.doubleValue()} instead of
 *       {@code getNumericCellValue()}), so a numeric cell renders identically either way.</li>
 *   <li>Boolean cells are upper-cased ("TRUE"/"FALSE") to match POI's {@code DataFormatter} convention — FastExcel's
 *       own {@code getText()} would otherwise give lowercase "true"/"false".</li>
 *   <li>A missing cell within a row (short row) is treated as blank/"", matching POI's
 *       {@code RETURN_BLANK_AS_NULL} behavior, via {@code Row.hasCell(int)}.</li>
 * </ul>
 * <p>
 * <b>Not compiled or run in this sandbox.</b> This sandbox's egress allowlist blocks Maven Central (the same
 * limitation already noted for the Spring/POI code in RefreshLogicVerification), so this class could not be
 * {@code javac}'d or executed here the way the pure-logic classes were. It was written directly against
 * {@code fastexcel-reader}'s actual source (Cell.java, Row.java, CellType.java) and README fetched from GitHub,
 * not from memory — but it still needs a real {@code mvn compile}/{@code mvn test} and a run of
 * {@code ExcelReaderComparison} against your real files before anyone trusts its output.
 */
public final class FastExcelRowReader implements ExcelRowReader {

    private static final DecimalFormat WHOLE_NUMBER = new DecimalFormat("#");

    private static String cellToString(Cell cell) {
        if (cell == null || cell.getType() == CellType.EMPTY) {
            return "";
        }
        if (cell.getType() == CellType.NUMBER || cell.getType() == CellType.FORMULA) {
            // NUMBER: mirrors PoiExcelRowReader's NUMERIC branch exactly, just sourced from FastExcel's
            // BigDecimal instead of POI's double -- same WHOLE_NUMBER/Double.toString rendering either way.
            // FORMULA: see the class javadoc's FORMULA caveat -- no number-format style is applied here,
            // where PoiExcelRowReader's FORMULA branch *did* apply style via DataFormatter. That's the one
            // documented behavioral delta, untested because the real source files have no formula cells.
            //
            // NOTE: Cell.asNumber() only works for CellType.NUMBER -- it has an internal type guard that
            // throws for FORMULA (confirmed from fastexcel's own Cell.java). A formula's cached numeric
            // result instead lives in getRawValue() as a plain string (the same field asDate() reads for
            // formula cells), so that's parsed directly here rather than going through asNumber().
            Double v = cell.getType() == CellType.NUMBER
                    ? cell.asNumber().doubleValue()
                    : parseRawValueAsDoubleOrNull(cell);
            if (v != null) {
                if (v == Math.floor(v) && !Double.isInfinite(v)) {
                    return WHOLE_NUMBER.format(v);
                }
                return Double.toString(v);
            }
            // Formula result wasn't numeric (e.g. a text formula) -- fall through to plain text rendering.
            return cell.getText();
        }
        if (cell.getType() == CellType.BOOLEAN) {
            // POI's DataFormatter renders booleans as "TRUE"/"FALSE"; FastExcel's getText() would give
            // lowercase "true"/"false" -- upper-cased here deliberately to match.
            return cell.getText().toUpperCase();
        }
        // STRING, ERROR, and anything else: plain text rendering (see the ERROR-cell caveat in the class javadoc).
        return cell.getText();
    }

    private static Double parseRawValueAsDoubleOrNull(Cell cell) {
        String raw = cell.getRawValue();
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException notNumeric) {
            return null;
        }
    }

    @Override
    public List<Map<String, String>> readFirstSheet(Path xlsxPath) throws IOException {
        try (InputStream in = new FileInputStream(xlsxPath.toFile());
             ReadableWorkbook wb = new ReadableWorkbook(in)) {
            Sheet sheet = wb.getFirstSheet();

            List<Map<String, String>> rows = new ArrayList<>();
            List<String> headers = new ArrayList<>();
            boolean[] sawHeader = {false};

            try (Stream<Row> rowStream = sheet.openStream()) {
                rowStream.forEach(row -> {
                    if (!sawHeader[0]) {
                        int count = row.getCellCount();
                        for (int c = 0; c < count; c++) {
                            Cell cell = row.hasCell(c) ? row.getCell(c) : null;
                            headers.add(cellToString(cell).trim());
                        }
                        sawHeader[0] = true;
                        return;
                    }
                    Map<String, String> record = new LinkedHashMap<>();
                    boolean allBlank = true;
                    for (int c = 0; c < headers.size(); c++) {
                        Cell cell = row.hasCell(c) ? row.getCell(c) : null;
                        String value = cellToString(cell).trim();
                        if (!value.isEmpty()) {
                            allBlank = false;
                        }
                        record.put(headers.get(c), value);
                    }
                    if (!allBlank) {
                        rows.add(record);
                    }
                });
            }

            if (!sawHeader[0]) {
                throw new IOException("Sheet has no header row: " + xlsxPath);
            }
            return rows;
        }
    }
}
