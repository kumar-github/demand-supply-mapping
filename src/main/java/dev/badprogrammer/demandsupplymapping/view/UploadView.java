package dev.badprogrammer.demandsupplymapping.view;

import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import dev.badprogrammer.demandsupplymapping.entity.RefreshRun;
import dev.badprogrammer.demandsupplymapping.matching.MatchingRunService;
import dev.badprogrammer.demandsupplymapping.refresh.AafdRefreshService;
import dev.badprogrammer.demandsupplymapping.refresh.RefreshService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Runs a supply, AAFD, or demand refresh from an uploaded Excel file, and a matching run on demand. Calls
 * RefreshService/AafdRefreshService/MatchingRunService directly, in-process — NOT the REST multipart endpoints in
 * RefreshController (see that class's Javadoc for why: this is a Vaadin view, so per this app's own architecture it
 * goes straight to the service layer rather than round-tripping through HTTP to itself).
 * <p>
 * Each Upload widget accepts one .xlsx file, buffers it in memory (small files — the real exports are a few hundred
 * rows), stages it to a temp file (the refresh services need a real Path, not a stream), runs the matching refresh,
 * shows the resulting row counts, and cleans the temp file up.
 * <p>
 * The AAFD section (added 2026-10-08, per explicit instruction) uploads into its own aafd_supply_enriched table — see
 * AafdRefreshService's Javadoc for why that's a separate table from supply_enriched, not a column on it.
 * <p>
 * The "Run matching" section (added 2026-10-08, per explicit instruction, location chosen as this page: it's the
 * natural next step after uploading supply/demand, and the user flagged that without it, new uploads don't show up in
 * the Dashboard's "Match quality across active bench" card or populate match_candidates until someone calls POST
 * /api/matching/run from outside the app). It's a manual button, not an auto-trigger after each upload — running it
 * once after both supply and demand are refreshed avoids redundant double-runs if both files are uploaded in the same
 * visit.
 */
@Route(value = "upload", layout = MainLayout.class)
@PageTitle("Upload")
public class UploadView extends VerticalLayout {

    private final RefreshService refreshService;
    private final AafdRefreshService aafdRefreshService;
    private final MatchingRunService matchingRunService;

    public UploadView(RefreshService refreshService, AafdRefreshService aafdRefreshService,
                      MatchingRunService matchingRunService) {
        this.refreshService = refreshService;
        this.aafdRefreshService = aafdRefreshService;
        this.matchingRunService = matchingRunService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(buildUploadSection("Supply", "Upload the AFD-Supply.xlsx export to refresh supply_enriched.", "supply"));
        add(buildUploadSection("AAFD Supply", "Upload the AAFD-Supply.xlsx export to refresh aafd_supply_enriched (a separate table from Supply — see the AAFD Supply tab).", "aafd"));
        add(buildUploadSection("Demand", "Upload the Demand.xlsx export to refresh demand_enriched.", "demand"));
        add(buildMatchingSection());
    }

    private VerticalLayout buildUploadSection(String title, String helpText, String kind) {
        MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(".xlsx");
        upload.setMaxFiles(1);
        upload.setDropAllowed(true);

        Div result = new Div();

        upload.addSucceededListener(event -> {
            String fileName = event.getFileName();
            Path staged = null;
            try {
                staged = stage(buffer.getInputStream(), fileName);
                RefreshRun run = switch (kind) {
                    case "supply" -> refreshService.refreshSupply(staged);
                    case "demand" -> refreshService.refreshDemand(staged);
                    default -> aafdRefreshService.refreshAafd(staged);
                };
                showResult(result, run);
                Notification success = Notification.show(title + " refresh finished: " + run.getStatus());
                success.addThemeVariants(NotificationVariant.SUCCESS);
            } catch (Exception e) {
                result.removeAll();
                result.add(new Text("Refresh failed: " + e.getMessage()));
                Notification failure = Notification.show("Refresh failed — see details below.");
                failure.addThemeVariants(NotificationVariant.ERROR);
            } finally {
                deleteQuietly(staged);
            }
        });

        upload.addFileRejectedListener(event -> {
            Notification n = Notification.show(event.getErrorMessage());
            n.addThemeVariants(NotificationVariant.ERROR);
        });

        VerticalLayout section = new VerticalLayout(new H3(title), new Div(new Text(helpText)), upload, result);
        section.setPadding(false);
        section.setSpacing(true);
        return section;
    }

    /**
     * Manual trigger for MatchingRunService.runMatching() — see this class's Javadoc for why it's manual and why it
     * lives on this page. Uses its own result renderer (showMatchingResult) rather than showResult(): a matching run's
     * RefreshRun has rowsChanged always null (a full rewrite has no "changed" concept) and different meaning for
     * rowsIn/rowsNew/rowsFlagged, so relabeling avoids a misleading "Changed: null" / generic "Rows in" field.
     */
    private VerticalLayout buildMatchingSection() {
        Div result = new Div();
        Button runButton = new Button("Run matching", e -> {
            try {
                RefreshRun run = matchingRunService.runMatching();
                showMatchingResult(result, run);
                Notification success = Notification.show("Matching run finished: " + run.getStatus());
                success.addThemeVariants(NotificationVariant.SUCCESS);
            } catch (Exception ex) {
                result.removeAll();
                result.add(new Text("Matching run failed: " + ex.getMessage()));
                Notification failure = Notification.show("Matching run failed — see details below.");
                failure.addThemeVariants(NotificationVariant.ERROR);
            }
        });

        Div help = new Div(new Text("Recomputes match_candidates from the current active, classified Supply and "
                + "Demand rows. Run this after uploading new Supply/Demand data so the Dashboard's match-quality "
                + "numbers reflect it — uploading alone doesn't trigger a matching run."));

        VerticalLayout section = new VerticalLayout(new H3("Run Matching"), help, runButton, result);
        section.setPadding(false);
        section.setSpacing(true);
        return section;
    }

    private void showResult(Div result, RefreshRun run) {
        result.removeAll();
        HorizontalLayout counts = new HorizontalLayout(
                new Text("Status: " + run.getStatus()),
                new Text("Rows in: " + run.getRowsIn()),
                new Text("New: " + run.getRowsNew()),
                new Text("Changed: " + run.getRowsChanged()),
                new Text("Flagged: " + run.getRowsFlagged())
        );
        counts.setSpacing(true);
        result.add(counts);
        if (run.getErrorMessage() != null) {
            result.add(new Div(new Text("Error: " + run.getErrorMessage())));
        }
    }

    private void showMatchingResult(Div result, RefreshRun run) {
        result.removeAll();
        HorizontalLayout counts = new HorizontalLayout(
                new Text("Status: " + run.getStatus()),
                new Text("Supply considered: " + run.getRowsIn()),
                new Text("Match rows written: " + run.getRowsNew()),
                new Text("Excluded near-misses: " + run.getRowsFlagged())
        );
        counts.setSpacing(true);
        result.add(counts);
        if (run.getErrorMessage() != null) {
            result.add(new Div(new Text("Error: " + run.getErrorMessage())));
        }
    }

    private Path stage(InputStream in, String originalFileName) throws IOException {
        String suffix = (originalFileName != null && originalFileName.contains("."))
                ? originalFileName.substring(originalFileName.lastIndexOf('.'))
                : ".xlsx";
        Path staged = Files.createTempFile("benchmatch-upload-", suffix);
        try (in) {
            Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
        }
        return staged;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }
}
