package dev.badprogrammer.demandsupplymapping.view;

import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.streams.UploadHandler;
import dev.badprogrammer.demandsupplymapping.entity.RefreshRun;
import dev.badprogrammer.demandsupplymapping.matching.MatchingRunService;
import dev.badprogrammer.demandsupplymapping.refresh.AafdRefreshService;
import dev.badprogrammer.demandsupplymapping.refresh.RefreshService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Runs a supply, AAFD, or demand refresh from an uploaded Excel file, and a matching run on demand. Calls
 * RefreshService/AafdRefreshService/MatchingRunService directly, in-process — NOT the REST multipart endpoints in
 * RefreshController (see that class's Javadoc for why: this is a Vaadin view, so per this app's own architecture it
 * goes straight to the service layer rather than round-tripping through HTTP to itself).
 * <p>
 * Each Upload widget accepts one .xlsx file. {@code UploadHandler.toTempFile(...)} (the non-deprecated replacement
 * for the old {@code MemoryBuffer}-backed {@code Upload}, swapped 2026-10-09) stages the upload straight to a temp
 * file itself — the refresh services need a real {@code Path}, not a stream — and hands the callback that finished
 * {@code File} directly; the callback still deletes it once the refresh runs, since per {@code UploadHandler}'s own
 * Javadoc temp files are NOT cleaned up automatically. The callback runs on the UI thread (same as the old
 * {@code addSucceededListener}), so updating {@code result}/firing a {@code Notification} from inside it needs no
 * extra {@code UI.access(...)} wrapping.
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

        add(buildUploadSection("AFD Supply", "Upload the AFD-Supply.xlsx export to refresh supply_enriched.", "supply"));
        add(buildUploadSection("AAFD Supply", "Upload the AAFD-Supply.xlsx export to refresh aafd_supply_enriched (a separate table from Supply — see the AAFD Supply tab).", "aafd"));
        add(buildUploadSection("Demand", "Upload the Demand.xlsx export to refresh demand_enriched.", "demand"));
        add(buildMatchingSection());
    }

    private VerticalLayout buildUploadSection(String title, String helpText, String kind) {
        Div result = new Div();

        UploadHandler uploadHandler = UploadHandler.toTempFile((metadata, file) -> {
            try {
                RefreshRun run = switch (kind) {
                    case "supply" -> refreshService.refreshSupply(file.toPath());
                    case "demand" -> refreshService.refreshDemand(file.toPath());
                    default -> aafdRefreshService.refreshAafd(file.toPath());
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
                deleteQuietly(file);
            }
        });

        Upload upload = new Upload(uploadHandler);
        upload.setAcceptedFileTypes(".xlsx");
        upload.setMaxFiles(1);
        upload.setDropAllowed(true);

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
        // Span, not Text: Text renders as a bare DOM text node, and HorizontalLayout's setSpacing(true) works via
        // flexbox gap — gap only inserts space between element children, not between text nodes, so with Text here
        // every field ran together with no space at all ("Status: succeededRows in: 1461New: 262..."). Span is a
        // real element, so the same gap now actually separates them.
        HorizontalLayout counts = new HorizontalLayout(
                new Span("Status: " + run.getStatus()),
                new Span("Rows in: " + run.getRowsIn()),
                new Span("New: " + run.getRowsNew()),
                new Span("Changed: " + run.getRowsChanged()),
                new Span("Flagged: " + run.getRowsFlagged())
        );
        counts.setSpacing(true);
        result.add(counts);
        if (run.getErrorMessage() != null) {
            result.add(new Div(new Text("Error: " + run.getErrorMessage())));
        }
    }

    private void showMatchingResult(Div result, RefreshRun run) {
        result.removeAll();
        // Same Text -> Span fix as showResult() above, same reason.
        HorizontalLayout counts = new HorizontalLayout(
                new Span("Status: " + run.getStatus()),
                new Span("Supply considered: " + run.getRowsIn()),
                new Span("Match rows written: " + run.getRowsNew()),
                new Span("Excluded near-misses: " + run.getRowsFlagged())
        );
        counts.setSpacing(true);
        result.add(counts);
        if (run.getErrorMessage() != null) {
            result.add(new Div(new Text("Error: " + run.getErrorMessage())));
        }
    }

    private void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }
}
