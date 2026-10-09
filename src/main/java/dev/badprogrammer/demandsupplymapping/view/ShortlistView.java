package dev.badprogrammer.demandsupplymapping.view;

import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;
import dev.badprogrammer.demandsupplymapping.export.ShortlistWorkbookService;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;

/**
 * "Generate" buttons for both shortlist workbooks — the employee-centric one (task #19) and the demand-centric one
 * (added 2026-10-03, see ShortlistWorkbookService.generateByDemand()'s Javadoc for why it exists: TAG/TSC, who fulfill
 * one demand at a time, found the employee-grouped sheet unusable at the volume the engine now produces). Both are the
 * Vaadin-side counterpart to ShortlistExportController's REST downloads, calling ShortlistWorkbookService directly,
 * in-process, same as UploadView calls RefreshService — see that class's Javadoc for the architecture rationale.
 * <p>
 * Each download is an Anchor wired to a {@code DownloadHandler} rather than a plain Button, because that's what makes
 * the browser actually save a file: the handler's callback only runs when the link is clicked, so nothing is computed
 * just by opening this page ({@code DownloadHandler.fromInputStream(...)} replaced the deprecated
 * {@code StreamResource} here on 2026-10-09 — same lazy-on-click contract, see its own Javadoc). The by-demand
 * {@code DownloadHandler} is still rebuilt fresh on every click, exactly like the {@code StreamResource} it replaced
 * was — NOT optional busywork: {@code InputStreamDownloadCallback.complete(...)} runs with no
 * {@code UI.access()}/session-lock wrapping (confirmed from Vaadin's own {@code InputStreamDownloadHandler} source —
 * a download callback is meant to only produce bytes, same contract the old {@code StreamResource} supplier had), so
 * reading a live field's value from inside it would be touching Vaadin UI state off the UI thread without the lock.
 * {@code demandLimitField.getValue()} is read on the UI thread, in the Button's click listener (which does hold the
 * session lock), resolved into a plain {@code int}, and only that already-resolved value is captured into the
 * handler — the handler itself never touches the field. Neither Anchor sets the HTML {@code download} attribute
 * explicitly any more: {@code Anchor}'s {@code DownloadHandler} constructor/{@code setHref} default to
 * {@code AttachmentType.DOWNLOAD} for a non-inline handler (which {@code fromInputStream(...)} is), so the browser
 * is told to save the file via the response's {@code Content-Disposition} header instead, the same outcome the old
 * attribute produced.
 */
@Route(value = "shortlist", layout = MainLayout.class)
@PageTitle("Shortlist")
public class ShortlistView extends VerticalLayout {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public ShortlistView(ShortlistWorkbookService workbookService) {
        setPadding(true);
        setSpacing(true);

        H3 employeeTitle = new H3("Matching Shortlist Workbook — by Employee");
        Div employeeHelpText = new Div(new Text(
                "Runs a fresh matching pass over active, classified supply and demand and builds the Phase 1 "
                        + "Shortlist workbook — one employee-grouped sheet plus a Read Me / methodology sheet. "
                        + "Always reflects current data, not a previous matching run. For the team proposing "
                        + "supply (ranked demands per employee)."));

        DownloadHandler employeeHandler = DownloadHandler.fromInputStream(event -> new DownloadResponse(
                new ByteArrayInputStream(workbookService.generate()),
                "phase1-matching-shortlist-" + LocalDate.now() + ".xlsx",
                XLSX_CONTENT_TYPE,
                -1));

        Anchor employeeDownload = new Anchor(employeeHandler, "");
        Button employeeGenerateButton = new Button("Generate & Download");
        employeeGenerateButton.addThemeVariants(ButtonVariant.PRIMARY);
        employeeDownload.add(employeeGenerateButton);

        add(employeeTitle, employeeHelpText, employeeDownload, new Hr());

        H3 demandTitle = new H3("Matching Shortlist Workbook — by Demand");
        Div demandHelpText = new Div(new Text(
                "Same matching pass, grouped by demand instead of employee — for the team fulfilling specific "
                        + "demands. Only demands with a Strong/Good candidate are included (the rest land on a "
                        + "'No Coverage' tab), capped to the most urgent demands below so each run is an "
                        + "actionable weekly worklist rather than the entire open portfolio."));

        IntegerField demandLimitField = new IntegerField("Demands per export");
        demandLimitField.setValue(ShortlistWorkbookService.DEFAULT_MAX_DEMANDS_PER_RUN);
        demandLimitField.setMin(1);
        demandLimitField.setStepButtonsVisible(true);
        demandLimitField.setHelperText("A placeholder, not a measured capacity — adjust until it matches what "
                + "TAG/TSC can actually work through in a week.");
        demandLimitField.setWidth("260px");

        // Rebuilt fresh on every click (same as the old rebuilt-StreamResource-per-click approach, just swapping in
        // DownloadHandler) — NOT because the handler API requires it, but because demandLimitField.getValue() has to
        // be read here, in the Button's click listener, which runs on the UI thread with the session lock held.
        // Unlike an upload's FileUploadCallback, a download's InputStreamDownloadCallback is invoked by
        // InputStreamDownloadHandler with NO UI.access()/session-lock wrapping (confirmed from Vaadin's own source —
        // it's meant to only produce bytes, same contract the old StreamResource supplier had). Reading a live
        // Vaadin field's value from inside that callback would be reading it off the UI thread without the lock —
        // so the value is resolved here, on the UI thread, into a plain captured int, and only that int crosses into
        // the callback.
        Anchor demandDownloadAnchor = new Anchor();
        demandDownloadAnchor.getStyle().set("display", "none");
        Button demandGenerateButton = new Button("Generate & Download", e -> {
            int limit = demandLimitField.getValue() == null
                    ? ShortlistWorkbookService.DEFAULT_MAX_DEMANDS_PER_RUN : demandLimitField.getValue();
            DownloadHandler demandHandler = DownloadHandler.fromInputStream(event -> new DownloadResponse(
                    new ByteArrayInputStream(workbookService.generateByDemand(limit)),
                    "phase1-matching-shortlist-by-demand-" + LocalDate.now() + ".xlsx",
                    XLSX_CONTENT_TYPE,
                    -1));
            demandDownloadAnchor.setHref(demandHandler);
            demandDownloadAnchor.getElement().executeJs("this.click()");
        });
        demandGenerateButton.addThemeVariants(ButtonVariant.PRIMARY);

        HorizontalLayout demandControls = new HorizontalLayout(demandLimitField, demandGenerateButton);
        demandControls.setAlignItems(Alignment.BASELINE);

        add(demandTitle, demandHelpText, demandControls, demandDownloadAnchor);
    }
}
