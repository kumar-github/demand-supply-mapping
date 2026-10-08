package dev.badprogrammer.demandsupplymapping.refresh.excel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Standalone (no JUnit/Spring) harness that runs {@link PoiExcelRowReader} and {@link FastExcelRowReader} against the
 * same real .xlsx file and reports every difference between them -- the actual check to run before trusting
 * {@code EXCEL_READER_IMPL=fastexcel} in production, rather than taking the javadoc caveats in
 * {@code FastExcelRowReader} on faith.
 * <p>
 * Needs both Apache POI (poi-ooxml) and fastexcel-reader on the classpath, so (same caveat as RefreshLogicVerification)
 * it can't run in a sandbox without Maven Central -- run it on a real machine:
 * <p>
 * cd bench-match-webapp mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt javac -d out -cp "$(cat cp.txt)" \
 * src/main/java/com/example/benchmatch/refresh/excel/*.java \
 * src/test/java/com/example/benchmatch/refresh/excel/ExcelReaderComparison.java java -cp "out:$(cat cp.txt)"
 * com.example.benchmatch.refresh.excel.ExcelReaderComparison \ ../AFD-Supply.xlsx ../Demand.xlsx ../AAFD-Supply.xlsx
 * <p>
 * (any number of file paths; each is checked independently and the run exits non-zero if any file disagrees.)
 */
public class ExcelReaderComparison {

    private static final int MAX_DIFFS_SHOWN_PER_FILE = 20;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: ExcelReaderComparison <file1.xlsx> [file2.xlsx ...]");
            System.exit(2);
        }

        boolean allIdentical = true;
        for (String arg : args) {
            allIdentical &= compare(Path.of(arg));
        }

        System.out.println();
        System.out.println(allIdentical
                ? "RESULT: IDENTICAL — PoiExcelRowReader and FastExcelRowReader agree on every file given."
                : "RESULT: DIFFERENCES FOUND — see above. Do not switch EXCEL_READER_IMPL=fastexcel for an "
                + "affected file until these are understood (likely the FORMULA/ERROR-cell caveats "
                + "documented in FastExcelRowReader's javadoc, if the file actually has formula cells).");
        if (!allIdentical) {
            System.exit(1);
        }
    }

    private static boolean compare(Path xlsxPath) {
        System.out.println("== " + xlsxPath + " ==");
        List<Map<String, String>> poiRows;
        List<Map<String, String>> fastExcelRows;
        try {
            poiRows = ExcelRowReaders.poi().readFirstSheet(xlsxPath);
        } catch (IOException e) {
            System.out.println("  POI reader FAILED: " + e);
            return false;
        }
        try {
            fastExcelRows = ExcelRowReaders.fastExcel().readFirstSheet(xlsxPath);
        } catch (IOException e) {
            System.out.println("  FastExcel reader FAILED: " + e);
            return false;
        }

        boolean identical = true;
        if (poiRows.size() != fastExcelRows.size()) {
            System.out.println("  ROW COUNT MISMATCH: poi=" + poiRows.size() + " fastexcel=" + fastExcelRows.size());
            identical = false;
        }

        int rowsToCompare = Math.min(poiRows.size(), fastExcelRows.size());
        int diffsShown = 0;
        int diffCount = 0;
        for (int r = 0; r < rowsToCompare; r++) {
            Map<String, String> poiRow = poiRows.get(r);
            Map<String, String> fastExcelRow = fastExcelRows.get(r);

            if (!poiRow.keySet().equals(fastExcelRow.keySet())) {
                System.out.println("  Row " + r + ": HEADER SET MISMATCH poi=" + poiRow.keySet()
                        + " fastexcel=" + fastExcelRow.keySet());
                identical = false;
                continue;
            }
            for (String header : poiRow.keySet()) {
                String poiValue = poiRow.get(header);
                String fastExcelValue = fastExcelRow.get(header);
                if (!poiValue.equals(fastExcelValue)) {
                    diffCount++;
                    identical = false;
                    if (diffsShown < MAX_DIFFS_SHOWN_PER_FILE) {
                        System.out.println("  Row " + r + ", column \"" + header + "\": poi=\"" + poiValue
                                + "\" fastexcel=\"" + fastExcelValue + "\"");
                        diffsShown++;
                    }
                }
            }
        }
        if (diffCount > diffsShown) {
            System.out.println("  ... " + (diffCount - diffsShown) + " more cell difference(s) not shown.");
        }

        System.out.println(identical
                ? "  OK — " + rowsToCompare + " rows, every cell identical."
                : "  " + diffCount + " cell difference(s) across " + rowsToCompare + " compared rows.");
        return identical;
    }
}
