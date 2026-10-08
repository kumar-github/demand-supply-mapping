package dev.badprogrammer.demandsupplymapping.refresh.excel;

/**
 * Switches between the two {@link ExcelRowReader} implementations without touching any caller.
 * {@code ExcelSheetReader.readFirstSheet(Path)} (the stable entry point every existing caller already uses) delegates
 * to {@link #active()}, so flipping the switch below changes behavior everywhere at once -- no code change needed in
 * RefreshService, UploadView, or any test.
 * <p>
 * Controlled by either the {@code EXCEL_READER_IMPL} environment variable (handy for a deployed container -- set it in
 * Render's/Railway's Environment tab with no redeploy of code needed) or the {@code excel.reader.impl} JVM system
 * property (handy for a local {@code -D} flag); the env var wins if both are set. Accepted values: {@code "poi"}
 * (default -- the original, unchanged behavior) or {@code "fastexcel"} (the low-memory replacement, added 2026-10-02 --
 * see {@link FastExcelRowReader}'s javadoc for what's verified and what isn't).
 * <p>
 * Defaulting to {@code "poi"} is deliberate: it means adding this class and {@link FastExcelRowReader} changes nothing
 * about production behavior until someone explicitly opts in, which is the whole point of keeping both implementations
 * side by side instead of replacing one with the other outright.
 */
public final class ExcelRowReaders {

    public static final String ENV_VAR = "EXCEL_READER_IMPL";
    public static final String SYSTEM_PROPERTY = "excel.reader.impl";

    private static final ExcelRowReader POI = new PoiExcelRowReader();
    private static final ExcelRowReader FAST_EXCEL = new FastExcelRowReader();

    private ExcelRowReaders() {
    }

    /**
     * The original Apache POI / {@code XSSFWorkbook} based reader, regardless of what's configured.
     */
    public static ExcelRowReader poi() {
        return POI;
    }

    /**
     * The low-memory FastExcel-based reader, regardless of what's configured.
     */
    public static ExcelRowReader fastExcel() {
        return FAST_EXCEL;
    }

    /**
     * The reader that {@code ExcelSheetReader.readFirstSheet(Path)} actually uses, decided by {@value #ENV_VAR} /
     * {@value #SYSTEM_PROPERTY} (env var wins; defaults to {@code "poi"} if neither is set or the value isn't
     * recognized).
     */
    public static ExcelRowReader active() {
        String choice = System.getenv(ENV_VAR);
        if (choice == null || choice.isBlank()) {
            choice = System.getProperty(SYSTEM_PROPERTY);
        }
        if (choice == null) {
            return POI;
        }
        return switch (choice.trim().toLowerCase()) {
            case "fastexcel", "fast-excel", "fast_excel" -> FAST_EXCEL;
            case "poi", "" -> POI;
            default -> POI; // unrecognized value: fail safe to the original, well-understood behavior
        };
    }
}
