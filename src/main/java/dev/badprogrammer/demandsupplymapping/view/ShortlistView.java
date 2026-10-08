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
import com.vaadin.flow.server.StreamResource;
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
 * Each download is an Anchor with a StreamResource rather than a plain Button, because that's what makes the browser
 * actually save a file: the resource's content supplier only runs when the link is clicked, so nothing is computed just
 * by opening this page. The by-demand StreamResource is rebuilt on every click (new StreamResource per generate) so it
 * always reads the cap field's current value rather than whatever it was when the page loaded.
 */
@Route(value = "shortlist", layout = MainLayout.class)
@PageTitle("Shortlist")
public class ShortlistView extends VerticalLayout {

    public ShortlistView(ShortlistWorkbookService workbookService) {
        setPadding(true);
        setSpacing(true);

        H3 employeeTitle = new H3("Matching Shortlist Workbook — by Employee");
        Div employeeHelpText = new Div(new Text(
                "Runs a fresh matching pass over active, classified supply and demand and builds the Phase 1 "
                        + "Shortlist workbook — one employee-grouped sheet plus a Read Me / methodology sheet. "
                        + "Always reflects current data, not a previous matching run. For the team proposing "
                        + "supply (ranked demands per employee)."));

        StreamResource employeeResource = new StreamResource(
                "phase1-matching-shortlist-" + LocalDate.now() + ".xlsx",
                () -> new ByteArrayInputStream(workbookService.generate())
        );
        employeeResource.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        Anchor employeeDownload = new Anchor(employeeResource, "");
        employeeDownload.getElement().setAttribute("download", true);
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

        // Rebuilt fresh on every click via a Button + addClickListener (rather than one StreamResource bound to
        // an Anchor at construction time, as the employee download above uses) specifically so each download
        // reads demandLimitField's value AT CLICK TIME — a StreamResource created once at page-load would freeze
        // whatever the field held when the page opened, silently ignoring later edits to it.
        Anchor demandDownloadAnchor = new Anchor();
        demandDownloadAnchor.getElement().setAttribute("download", true);
        demandDownloadAnchor.getStyle().set("display", "none");
        Button demandGenerateButton = new Button("Generate & Download", e -> {
            int limit = demandLimitField.getValue() == null
                    ? ShortlistWorkbookService.DEFAULT_MAX_DEMANDS_PER_RUN : demandLimitField.getValue();
            StreamResource resource = new StreamResource(
                    "phase1-matching-shortlist-by-demand-" + LocalDate.now() + ".xlsx",
                    () -> new ByteArrayInputStream(workbookService.generateByDemand(limit))
            );
            resource.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            demandDownloadAnchor.setHref(resource);
            demandDownloadAnchor.getElement().executeJs("this.click()");
        });
        demandGenerateButton.addThemeVariants(ButtonVariant.PRIMARY);

        HorizontalLayout demandControls = new HorizontalLayout(demandLimitField, demandGenerateButton);
        demandControls.setAlignItems(Alignment.BASELINE);

        add(demandTitle, demandHelpText, demandControls, demandDownloadAnchor);
    }
}
