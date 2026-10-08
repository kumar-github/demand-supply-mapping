package dev.badprogrammer.demandsupplymapping.refresh;

import dev.badprogrammer.demandsupplymapping.refresh.excel.ExcelRowReaders;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Stable entry point every caller (RefreshService, UploadView, and every test) already uses. The actual reading logic
 * now lives in {@code com.example.benchmatch.refresh.excel} as of 2026-10-02, when a second implementation
 * ({@code FastExcelRowReader}) was added alongside the original ({@code PoiExcelRowReader}) to fix a real
 * OutOfMemoryError on Render's free tier -- see DEPLOY.md, "Known fix #2".
 * <p>
 * This class is deliberately unchanged in its public contract: {@link #readFirstSheet(Path)} does exactly what it
 * always did, just delegating to whichever reader {@code ExcelRowReaders.active()} resolves to (POI by default -- see
 * {@code ExcelRowReaders}' javadoc for how to switch, and {@code ExcelReaderComparison} in src/test for the tool to
 * verify the two readers agree on your real files before switching in production).
 */
public final class ExcelSheetReader {

    private ExcelSheetReader() {
    }

    /**
     * Reads the first sheet into a list of header-&gt;value maps, values as Strings.
     */
    public static List<Map<String, String>> readFirstSheet(Path xlsxPath) throws IOException {
        return ExcelRowReaders.active().readFirstSheet(xlsxPath);
    }
}
