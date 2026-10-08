package dev.badprogrammer.demandsupplymapping.export;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Download endpoints for the shortlist workbooks (task #19, plus the by-demand export added 2026-10-03) — the REST-side
 * counterpart to the Vaadin "Generate" buttons in ShortlistView, for any consumer outside the browser. All call
 * ShortlistWorkbookService directly; this controller only adds the HTTP download headers.
 */
@RestController
public class ShortlistExportController {

    private final ShortlistWorkbookService workbookService;

    public ShortlistExportController(ShortlistWorkbookService workbookService) {
        this.workbookService = workbookService;
    }

    @GetMapping("/api/shortlist/workbook")
    public ResponseEntity<byte[]> downloadShortlistWorkbook() {
        byte[] bytes = workbookService.generate();
        String fileName = "phase1-matching-shortlist-" + LocalDate.now() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    // "limit" lets the weekly worklist size be tuned from outside the codebase (e.g. a scheduled job's URL)
    // without a redeploy — see ShortlistWorkbookService.DEFAULT_MAX_DEMANDS_PER_RUN's Javadoc for why this is a
    // placeholder number rather than a measured one.
    @GetMapping("/api/shortlist/workbook/by-demand")
    public ResponseEntity<byte[]> downloadShortlistWorkbookByDemand(
            @RequestParam(name = "limit", defaultValue = "" + ShortlistWorkbookService.DEFAULT_MAX_DEMANDS_PER_RUN) int limit) {
        byte[] bytes = workbookService.generateByDemand(limit);
        String fileName = "phase1-matching-shortlist-by-demand-" + LocalDate.now() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
