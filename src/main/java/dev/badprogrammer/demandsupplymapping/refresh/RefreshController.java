package dev.badprogrammer.demandsupplymapping.refresh;

import dev.badprogrammer.demandsupplymapping.entity.RefreshRun;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Two ways in to RefreshService:
 * <p>
 * 1. {@code filePath}-based endpoints — unchanged from the earlier scaffold. Still useful for scripting a refresh
 * against a file already sitting on the server (e.g. the CLI tool's input/ folder) without going through a browser.
 * <p>
 * 2. Multipart {@code /upload} endpoints (added for task #18) — accept the Excel file itself in the request body, for
 * any external consumer (curl/Postman/another service) that isn't the Vaadin UI. The Vaadin upload view
 * ({@code com.example.benchmatch.view.UploadView}) does NOT call these over HTTP; per this app's own architecture (see
 * BenchMatchApplication's Javadoc — "Vaadin views call the service layer in-process"), it calls {@link RefreshService}
 * directly. These multipart endpoints exist for everyone else.
 */
@RestController
public class RefreshController {

    private final RefreshService refreshService;

    public RefreshController(RefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @PostMapping("/api/refresh/supply")
    public Map<String, Object> refreshSupply(@RequestParam String filePath) {
        RefreshRun run = refreshService.refreshSupply(Path.of(filePath));
        return summarize(run);
    }

    @PostMapping("/api/refresh/demand")
    public Map<String, Object> refreshDemand(@RequestParam String filePath) {
        RefreshRun run = refreshService.refreshDemand(Path.of(filePath));
        return summarize(run);
    }

    @PostMapping("/api/refresh/supply/upload")
    public Map<String, Object> refreshSupplyUpload(@RequestParam("file") MultipartFile file) {
        Path staged = stageUpload(file);
        try {
            return summarize(refreshService.refreshSupply(staged));
        } finally {
            deleteQuietly(staged);
        }
    }

    @PostMapping("/api/refresh/demand/upload")
    public Map<String, Object> refreshDemandUpload(@RequestParam("file") MultipartFile file) {
        Path staged = stageUpload(file);
        try {
            return summarize(refreshService.refreshDemand(staged));
        } finally {
            deleteQuietly(staged);
        }
    }

    /**
     * Multipart uploads arrive as an in-memory/temp-backed {@link MultipartFile}, but RefreshService (and the Apache
     * POI reader underneath it, via ExcelSheetReader) needs a real {@link Path}. Stages the upload into a process-temp
     * file under a dedicated prefix, deleted again in a {@code finally} block once the refresh finishes (success or
     * failure) — nothing downstream needs the file after the DB write, and {@code deleteOnExit} alone would leak files
     * for the life of a long-running server.
     */
    private Path stageUpload(MultipartFile file) {
        String original = file.getOriginalFilename();
        String suffix = (original != null && original.contains(".")) ? original.substring(original.lastIndexOf('.')) : ".xlsx";
        try {
            Path staged = Files.createTempFile("benchmatch-upload-", suffix);
            file.transferTo(staged);
            return staged;
        } catch (IOException e) {
            throw new UploadStagingException("Could not stage uploaded file '" + original + "'", e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup only — a leftover temp file is a non-issue compared to failing a completed refresh.
        }
    }

    private Map<String, Object> summarize(RefreshRun run) {
        return Map.of(
                "runId", run.getRunId(),
                "source", run.getSource(),
                "status", run.getStatus(),
                "rowsIn", run.getRowsIn(),
                "rowsNew", run.getRowsNew(),
                "rowsChanged", run.getRowsChanged(),
                "rowsFlagged", run.getRowsFlagged()
        );
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class UploadStagingException extends RuntimeException {
        public UploadStagingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
