package dev.badprogrammer.demandsupplymapping.refresh.excel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One implementation of "read the first sheet of an .xlsx file into a list of header-&gt;value maps".
 * <p>
 * There are two implementations side by side on purpose (see {@link ExcelRowReaders}): {@link PoiExcelRowReader} (the
 * original, Apache POI / {@code XSSFWorkbook} based) and {@link FastExcelRowReader} (the low-memory replacement, added
 * 2026-10-02 after a real OutOfMemoryError on Render's free tier — see DEPLOY.md). Both must produce the same output
 * for the same file; {@code ExcelReaderComparison} (in src/test) is the tool to check that against your real workbooks
 * before trusting a switch to FastExcel in production.
 */
public interface ExcelRowReader {

    /**
     * Reads the first sheet into a list of header-&gt;value maps, values as Strings. Same contract for every
     * implementation: row 0 is the header, every cell is rendered to a String, and a row is skipped only if every cell
     * in it is blank.
     */
    List<Map<String, String>> readFirstSheet(Path xlsxPath) throws IOException;
}
