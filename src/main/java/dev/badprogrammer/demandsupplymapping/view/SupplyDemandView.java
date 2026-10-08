package dev.badprogrammer.demandsupplymapping.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.dataview.GridLazyDataView;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.shared.Tooltip;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.*;
import dev.badprogrammer.demandsupplymapping.api.AafdSupplyQueryService;
import dev.badprogrammer.demandsupplymapping.api.DemandQueryService;
import dev.badprogrammer.demandsupplymapping.api.SupplyQueryService;
import dev.badprogrammer.demandsupplymapping.api.dto.AafdSupplyDto;
import dev.badprogrammer.demandsupplymapping.api.dto.DemandDto;
import dev.badprogrammer.demandsupplymapping.api.dto.SupplyDto;
import org.springframework.data.domain.Sort;

/**
 * Supply and Demand merged onto one tabbed page (2026-10-07 — these were two near-identical, read-only lookup grids
 * with no real value beyond browsing rows; see the "flag chip + popover" and "move both grids to one page" discussion
 * that led here). Replaces the former separate {@code SupplyView}/{@code DemandView} classes.
 * <p>
 * Both {@code /supply} and {@code /demand} still work as bookmarkable URLs — one class answers both via
 * {@code @Route}/{@code @RouteAlias}, and {@link #beforeEnter} reads which path was actually requested to select the
 * right tab on load, same as {@code DashboardView}'s own {@code @RouteAlias("")} does for its landing-page alias.
 * Switching tabs afterward doesn't trigger Flow navigation at all (it would rebuild this whole view and lose both
 * grids' filter state) — it swaps which pane is visible in place and calls {@code History.replaceState} so the visible
 * URL still matches the active tab (refresh/bookmark lands back on the same tab), without creating a new
 * browser-history entry per tab click.
 * <p>
 * Each pane's grid + data provider is built lazily, on first visit to that tab, not both up front — opening this page
 * on the Supply tab (the common case) shouldn't also fire a Demand count/page query nobody asked for yet. Whichever tab
 * {@link #beforeEnter} selects for the initial load is the one built immediately; the other only gets built the first
 * time it's actually clicked. Once built, a pane's grid/filters/data provider live for the rest of the page's lifetime
 * — switching tabs back and forth does NOT reset filters, unlike the old two-separate-routes setup, where navigating
 * away and back created a brand-new view (and so a blank filter bar) every time.
 * <p>
 * A third tab, "AAFD Supply" (added 2026-10-08, per explicit instruction), sits between "AFD Supply" (renamed from
 * plain "Supply" in the same change) and "Demand" — same lazy-build/pane-swap/history.replaceState treatment as the
 * other two, backed by {@link AafdSupplyQueryService} over the separate {@code aafd_supply_enriched} table. It's
 * deliberately simpler than the Supply/Demand panes: no persona/sub-persona/classification filters (AAFD isn't
 * classified yet — see AafdSupplyEnriched's Javadoc) and no flag-chip column (no mismatch concept exists for it), just
 * a filtered/paged grid of the raw AAFD export.
 */
@Route(value = "supply", layout = MainLayout.class)
@RouteAlias(value = "demand", layout = MainLayout.class)
@RouteAlias(value = "aafd-supply", layout = MainLayout.class)
@PageTitle("Supply & Demand")
public class SupplyDemandView extends VerticalLayout implements BeforeEnterObserver {

    private static final String PATH_SUPPLY = "supply";
    private static final String PATH_AAFD = "aafd-supply";
    private static final String PATH_DEMAND = "demand";

    // See SupplyView/DemandView's old DEFAULT_SORT Javadoc (now folded in here) — same reason: paging stays over a
    // stable row order even when no grid column is actively sorted.
    private static final Sort SUPPLY_DEFAULT_SORT = Sort.by("employeeId");
    private static final Sort AAFD_DEFAULT_SORT = Sort.by("empCode");
    private static final Sort DEMAND_DEFAULT_SORT = Sort.by("demandId");

    private final SupplyQueryService supplyQueryService;
    private final AafdSupplyQueryService aafdSupplyQueryService;
    private final DemandQueryService demandQueryService;

    private final Tab supplyTab = new Tab("AFD Supply");
    private final Tab aafdTab = new Tab("AAFD Supply");
    private final Tab demandTab = new Tab("Demand");
    private final Tabs tabs = new Tabs(supplyTab, aafdTab, demandTab);

    private final VerticalLayout supplyPane = new VerticalLayout();
    private final VerticalLayout aafdPane = new VerticalLayout();
    private final VerticalLayout demandPane = new VerticalLayout();
    // Supply-side filter fields/grid — unchanged from the old SupplyView.
    private final TextField supplyPersonaFilter = new TextField();
    private final TextField supplySubPersonaFilter = new TextField();
    private final TextField supplyLocationFilter = new TextField();
    private final TextField supplyClassificationStatusFilter = new TextField();
    private final Checkbox supplyActiveOnly = new Checkbox("Active only", true);
    private final Grid<SupplyDto> supplyGrid = new Grid<>(SupplyDto.class, false);
    // AAFD Supply-side filter fields/grid — simpler than Supply's (see class Javadoc): Band/Location/Capability only.
    private final TextField aafdBandFilter = new TextField();
    private final TextField aafdLocationFilter = new TextField();
    private final TextField aafdCapabilityFilter = new TextField();
    private final Checkbox aafdActiveOnly = new Checkbox("Active only", true);
    private final Grid<AafdSupplyDto> aafdGrid = new Grid<>(AafdSupplyDto.class, false);
    // Demand-side filter fields/grid — unchanged from the old DemandView.
    private final TextField demandPersonaFilter = new TextField();
    private final TextField demandSubPersonaFilter = new TextField();
    private final TextField demandLocationFilter = new TextField();
    private final TextField demandClassificationStatusFilter = new TextField();
    private final Checkbox demandActiveOnly = new Checkbox("Active only", true);
    private final Grid<DemandDto> demandGrid = new Grid<>(DemandDto.class, false);
    private boolean supplyBuilt = false;
    private boolean aafdBuilt = false;
    private boolean demandBuilt = false;
    private GridLazyDataView<SupplyDto> supplyDataView;
    private GridLazyDataView<AafdSupplyDto> aafdDataView;
    private GridLazyDataView<DemandDto> demandDataView;

    public SupplyDemandView(SupplyQueryService supplyQueryService, AafdSupplyQueryService aafdSupplyQueryService,
                            DemandQueryService demandQueryService) {
        this.supplyQueryService = supplyQueryService;
        this.aafdSupplyQueryService = aafdSupplyQueryService;
        this.demandQueryService = demandQueryService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        tabs.addClassName("bm-sd-tabs");
        tabs.addSelectedChangeListener(e -> onTabSelected());
        add(tabs);

        // "bm-sd-pane" (added 2026-10-08) gives styles.css something to pin min-height: 0 on — see
        // that class's comment for why a page-level vertical scrollbar was showing up alongside the
        // Grid's own internal one without it.
        supplyPane.setSizeFull();
        supplyPane.setPadding(false);
        supplyPane.setSpacing(true);
        supplyPane.addClassName("bm-sd-pane");
        aafdPane.setSizeFull();
        aafdPane.setPadding(false);
        aafdPane.setSpacing(true);
        aafdPane.setVisible(false);
        aafdPane.addClassName("bm-sd-pane");
        demandPane.setSizeFull();
        demandPane.setPadding(false);
        demandPane.setSpacing(true);
        demandPane.setVisible(false);
        demandPane.addClassName("bm-sd-pane");

        add(supplyPane, aafdPane, demandPane);
        setFlexGrow(1, supplyPane);
        setFlexGrow(1, aafdPane);
        setFlexGrow(1, demandPane);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String path = event.getLocation().getFirstSegment();
        Tab initialTab = PATH_DEMAND.equals(path) ? demandTab : PATH_AAFD.equals(path) ? aafdTab : supplyTab;
        // setSelectedTab() only fires the SelectedChangeListener when the selection actually
        // changes (never on first load, since supplyTab is already selected by default), so
        // onTabSelected() is also called explicitly below to guarantee the initial pane gets
        // built and shown regardless of which tab that turns out to be.
        tabs.setSelectedTab(initialTab);
        onTabSelected();
    }

    // ------------------------------------------------------------------
    // Supply pane
    // ------------------------------------------------------------------

    private void onTabSelected() {
        Tab selected = tabs.getSelectedTab();
        boolean aafdSelected = selected == aafdTab;
        boolean demandSelected = selected == demandTab;
        boolean supplySelected = !aafdSelected && !demandSelected;

        if (aafdSelected) {
            ensureAafdBuilt();
        } else if (demandSelected) {
            ensureDemandBuilt();
        } else {
            ensureSupplyBuilt();
        }
        supplyPane.setVisible(supplySelected);
        aafdPane.setVisible(aafdSelected);
        demandPane.setVisible(demandSelected);

        // Keep the address bar in sync with the active tab WITHOUT a real Flow navigation (which
        // would reconstruct this whole view, discarding all three grids' filter state). replaceState
        // doesn't add a browser-history entry either, so the back button isn't hijacked into
        // cycling tabs — it only matters for what a refresh or a bookmark lands on.
        String path = demandSelected ? PATH_DEMAND : aafdSelected ? PATH_AAFD : PATH_SUPPLY;
        UI.getCurrent().getPage().getHistory().replaceState(null, path);
    }

    private void ensureSupplyBuilt() {
        if (supplyBuilt) {
            return;
        }
        supplyBuilt = true;
        supplyPane.add(buildSupplyFilterBar(), buildSupplyGrid());
        setFlexGrow(1, supplyGrid);
        setupSupplyDataProvider();
    }

    private HorizontalLayout buildSupplyFilterBar() {
        supplyPersonaFilter.setPlaceholder("Persona");
        supplySubPersonaFilter.setPlaceholder("Sub-persona");
        supplyLocationFilter.setPlaceholder("Location");
        supplyClassificationStatusFilter.setPlaceholder("Classification status");

        Button search = new Button("Search", e -> supplyDataView.refreshAll());
        search.addThemeVariants(ButtonVariant.PRIMARY);
        Button clear = new Button("Clear", e -> {
            supplyPersonaFilter.clear();
            supplySubPersonaFilter.clear();
            supplyLocationFilter.clear();
            supplyClassificationStatusFilter.clear();
            supplyActiveOnly.setValue(true);
            supplyDataView.refreshAll();
        });

        HorizontalLayout bar = new HorizontalLayout(
                supplyPersonaFilter, supplySubPersonaFilter, supplyLocationFilter,
                supplyClassificationStatusFilter, supplyActiveOnly, search, clear
        );
        bar.setWidthFull();
        bar.setAlignItems(Alignment.BASELINE);
        return bar;
    }

    private Grid<SupplyDto> buildSupplyGrid() {
        // See the old SupplyView.buildGrid's Javadoc for the flexGrow(0)-vs-(1) reasoning — unchanged.
        supplyGrid.addClassName("bm-grid");
        supplyGrid.addColumn(SupplyDto::employeeId).setHeader("Employee ID").setSortable(true).setSortProperty("employeeId").setAutoWidth(true).setFlexGrow(0);
        supplyGrid.addColumn(SupplyDto::employeeName).setHeader("Name").setSortable(true).setSortProperty("employeeName").setAutoWidth(true).setFlexGrow(1);
        supplyGrid.addColumn(SupplyDto::band).setHeader("Band").setSortable(true).setSortProperty("band").setAutoWidth(true).setFlexGrow(0);
        supplyGrid.addColumn(SupplyDto::subBand).setHeader("Sub Band").setSortable(true).setSortProperty("subBand").setAutoWidth(true).setFlexGrow(0);
        supplyGrid.addColumn(SupplyDto::location).setHeader("Location").setSortable(true).setSortProperty("location").setAutoWidth(true).setFlexGrow(1);
        supplyGrid.addColumn(SupplyDto::persona).setHeader("Persona").setSortable(true).setSortProperty("persona.name").setAutoWidth(true).setFlexGrow(1);
        supplyGrid.addColumn(SupplyDto::subPersona).setHeader("Sub-persona").setSortable(true).setSortProperty("subPersona.name").setAutoWidth(true).setFlexGrow(1);
        supplyGrid.addColumn(SupplyDto::classificationStatus).setHeader("Classification").setSortable(true).setSortProperty("classificationStatus").setAutoWidth(true).setFlexGrow(1);
        // 2026-10-07: was a bare "⚠ Sub-capability mismatch" text column — now an always-visible
        // colored chip (so the flag doesn't need a hover to be noticed just to see THAT a row is
        // flagged). 2026-10-08: the chip's click-to-open-a-Dialog detail view (SupplyDto.
        // subCapabilityNote, previously fetched and never shown anywhere) was replaced by a plain
        // hover Tooltip carrying that same note text — see flagChip()'s own Javadoc for the full
        // reasoning (a Dialog + Close button was more interaction than a one-line note warranted,
        // and it left the cell in an odd "focused" state after closing it).
        supplyGrid.addComponentColumn(this::buildSupplyFlagChip).setHeader("Flag").setAutoWidth(true).setFlexGrow(0);
        supplyGrid.addColumn(SupplyDto::benchAgeingDays).setHeader("Bench Ageing (days)").setSortable(true).setSortProperty("benchAgeingDays").setAutoWidth(true).setFlexGrow(0);
        supplyGrid.addColumn(dto -> dto.isActive() ? "Active" : "Inactive").setHeader("Status").setAutoWidth(true).setFlexGrow(0);
        supplyGrid.addThemeVariants(GridVariant.ROW_STRIPES);
        supplyGrid.setSizeFull();
        supplyGrid.setPageSize(50);
        return supplyGrid;
    }

    private void setupSupplyDataProvider() {
        supplyDataView = supplyGrid.setItems(
                query -> supplyQueryService.page(
                        blankToNull(supplyPersonaFilter.getValue()),
                        blankToNull(supplySubPersonaFilter.getValue()),
                        blankToNull(supplyLocationFilter.getValue()),
                        blankToNull(supplyClassificationStatusFilter.getValue()),
                        Boolean.TRUE.equals(supplyActiveOnly.getValue()),
                        GridPagingUtil.toPageable(query, SUPPLY_DEFAULT_SORT)
                ).getContent().stream(),
                query -> (int) supplyQueryService.count(
                        blankToNull(supplyPersonaFilter.getValue()),
                        blankToNull(supplySubPersonaFilter.getValue()),
                        blankToNull(supplyLocationFilter.getValue()),
                        blankToNull(supplyClassificationStatusFilter.getValue()),
                        Boolean.TRUE.equals(supplyActiveOnly.getValue())
                )
        );
    }

    // ------------------------------------------------------------------
    // AAFD Supply pane
    // ------------------------------------------------------------------

    private Component buildSupplyFlagChip(SupplyDto dto) {
        if (!dto.subCapabilityMismatchFlag()) {
            return okChip();
        }
        return flagChip("amber", "Sub-capability mismatch", dto.subCapabilityNote());
    }

    private void ensureAafdBuilt() {
        if (aafdBuilt) {
            return;
        }
        aafdBuilt = true;
        aafdPane.add(buildAafdFilterBar(), buildAafdGrid());
        setFlexGrow(1, aafdGrid);
        setupAafdDataProvider();
    }

    private HorizontalLayout buildAafdFilterBar() {
        aafdBandFilter.setPlaceholder("Band");
        aafdLocationFilter.setPlaceholder("Location");
        aafdCapabilityFilter.setPlaceholder("Capability");

        Button search = new Button("Search", e -> aafdDataView.refreshAll());
        search.addThemeVariants(ButtonVariant.PRIMARY);
        Button clear = new Button("Clear", e -> {
            aafdBandFilter.clear();
            aafdLocationFilter.clear();
            aafdCapabilityFilter.clear();
            aafdActiveOnly.setValue(true);
            aafdDataView.refreshAll();
        });

        HorizontalLayout bar = new HorizontalLayout(
                aafdBandFilter, aafdLocationFilter, aafdCapabilityFilter, aafdActiveOnly, search, clear
        );
        bar.setWidthFull();
        bar.setAlignItems(Alignment.BASELINE);
        return bar;
    }

    private Grid<AafdSupplyDto> buildAafdGrid() {
        aafdGrid.addClassName("bm-grid");
        aafdGrid.addColumn(AafdSupplyDto::empCode).setHeader("Emp Code").setSortable(true).setSortProperty("empCode").setAutoWidth(true).setFlexGrow(0);
        aafdGrid.addColumn(AafdSupplyDto::name).setHeader("Name").setSortable(true).setSortProperty("name").setAutoWidth(true).setFlexGrow(1);
        aafdGrid.addColumn(AafdSupplyDto::band).setHeader("Band").setSortable(true).setSortProperty("band").setAutoWidth(true).setFlexGrow(0);
        aafdGrid.addColumn(AafdSupplyDto::subBand).setHeader("Sub Band").setSortable(true).setSortProperty("subBand").setAutoWidth(true).setFlexGrow(0);
        aafdGrid.addColumn(AafdSupplyDto::location).setHeader("Location").setSortable(true).setSortProperty("location").setAutoWidth(true).setFlexGrow(1);
        aafdGrid.addColumn(AafdSupplyDto::capability).setHeader("Capability").setSortable(true).setSortProperty("capability").setAutoWidth(true).setFlexGrow(1);
        aafdGrid.addColumn(AafdSupplyDto::subCapability).setHeader("Sub Capability").setSortable(true).setSortProperty("subCapability").setAutoWidth(true).setFlexGrow(1);
        aafdGrid.addColumn(AafdSupplyDto::primarySkill).setHeader("Primary Skill").setAutoWidth(true).setFlexGrow(1);
        aafdGrid.addColumn(AafdSupplyDto::availabilityDate).setHeader("Availability Date").setAutoWidth(true).setFlexGrow(0);
        aafdGrid.addColumn(AafdSupplyDto::customer).setHeader("Customer").setAutoWidth(true).setFlexGrow(1);
        aafdGrid.addColumn(AafdSupplyDto::deploymentStatus).setHeader("Deployment Status").setAutoWidth(true).setFlexGrow(0);
        aafdGrid.addColumn(dto -> dto.isActive() ? "Active" : "Inactive").setHeader("Status").setAutoWidth(true).setFlexGrow(0);
        aafdGrid.addThemeVariants(GridVariant.ROW_STRIPES);
        aafdGrid.setSizeFull();
        aafdGrid.setPageSize(50);
        return aafdGrid;
    }

    // ------------------------------------------------------------------
    // Demand pane
    // ------------------------------------------------------------------

    private void setupAafdDataProvider() {
        aafdDataView = aafdGrid.setItems(
                query -> aafdSupplyQueryService.page(
                        blankToNull(aafdBandFilter.getValue()),
                        blankToNull(aafdLocationFilter.getValue()),
                        blankToNull(aafdCapabilityFilter.getValue()),
                        Boolean.TRUE.equals(aafdActiveOnly.getValue()),
                        GridPagingUtil.toPageable(query, AAFD_DEFAULT_SORT)
                ).getContent().stream(),
                query -> (int) aafdSupplyQueryService.count(
                        blankToNull(aafdBandFilter.getValue()),
                        blankToNull(aafdLocationFilter.getValue()),
                        blankToNull(aafdCapabilityFilter.getValue()),
                        Boolean.TRUE.equals(aafdActiveOnly.getValue())
                )
        );
    }

    private void ensureDemandBuilt() {
        if (demandBuilt) {
            return;
        }
        demandBuilt = true;
        demandPane.add(buildDemandFilterBar(), buildDemandGrid());
        setFlexGrow(1, demandGrid);
        setupDemandDataProvider();
    }

    private HorizontalLayout buildDemandFilterBar() {
        demandPersonaFilter.setPlaceholder("Persona");
        demandSubPersonaFilter.setPlaceholder("Sub-persona");
        demandLocationFilter.setPlaceholder("Location");
        demandClassificationStatusFilter.setPlaceholder("Classification status");

        Button search = new Button("Search", e -> demandDataView.refreshAll());
        search.addThemeVariants(ButtonVariant.PRIMARY);
        Button clear = new Button("Clear", e -> {
            demandPersonaFilter.clear();
            demandSubPersonaFilter.clear();
            demandLocationFilter.clear();
            demandClassificationStatusFilter.clear();
            demandActiveOnly.setValue(true);
            demandDataView.refreshAll();
        });

        HorizontalLayout bar = new HorizontalLayout(
                demandPersonaFilter, demandSubPersonaFilter, demandLocationFilter,
                demandClassificationStatusFilter, demandActiveOnly, search, clear
        );
        bar.setWidthFull();
        bar.setAlignItems(Alignment.BASELINE);
        return bar;
    }

    private Grid<DemandDto> buildDemandGrid() {
        demandGrid.addClassName("bm-grid");
        demandGrid.addColumn(DemandDto::demandId).setHeader("Demand ID").setSortable(true).setSortProperty("demandId").setAutoWidth(true).setFlexGrow(0);
        demandGrid.addColumn(DemandDto::clusterNameRaw).setHeader("Cluster").setSortable(true).setSortProperty("clusterNameRaw").setAutoWidth(true).setFlexGrow(1);
        demandGrid.addColumn(DemandDto::location).setHeader("Location").setSortable(true).setSortProperty("location").setAutoWidth(true).setFlexGrow(1);
        demandGrid.addColumn(DemandDto::band).setHeader("Band").setSortable(true).setSortProperty("band").setAutoWidth(true).setFlexGrow(0);
        demandGrid.addColumn(DemandDto::persona).setHeader("Persona").setSortable(true).setSortProperty("persona.name").setAutoWidth(true).setFlexGrow(1);
        demandGrid.addColumn(DemandDto::subPersona).setHeader("Sub-persona").setSortable(true).setSortProperty("subPersona.name").setAutoWidth(true).setFlexGrow(1);
        demandGrid.addColumn(DemandDto::classificationStatus).setHeader("Classification").setSortable(true).setSortProperty("classificationStatus").setAutoWidth(true).setFlexGrow(1);
        // See buildSupplyGrid's comment above the equivalent Supply column — same treatment, this
        // time backed by DemandDto.masMappingNote (MAS-mapping mismatch), shown in red rather than
        // amber to keep it visually distinct from Supply's sub-capability mismatch chip.
        demandGrid.addComponentColumn(this::buildDemandFlagChip).setHeader("Flag").setAutoWidth(true).setFlexGrow(0);
        demandGrid.addColumn(dto -> dto.isActive() ? "Active" : "Inactive").setHeader("Status").setAutoWidth(true).setFlexGrow(0);
        demandGrid.addThemeVariants(GridVariant.ROW_STRIPES);
        demandGrid.setSizeFull();
        demandGrid.setPageSize(50);
        return demandGrid;
    }

    private void setupDemandDataProvider() {
        demandDataView = demandGrid.setItems(
                query -> demandQueryService.page(
                        blankToNull(demandPersonaFilter.getValue()),
                        blankToNull(demandSubPersonaFilter.getValue()),
                        blankToNull(demandLocationFilter.getValue()),
                        blankToNull(demandClassificationStatusFilter.getValue()),
                        Boolean.TRUE.equals(demandActiveOnly.getValue()),
                        GridPagingUtil.toPageable(query, DEMAND_DEFAULT_SORT)
                ).getContent().stream(),
                query -> (int) demandQueryService.count(
                        blankToNull(demandPersonaFilter.getValue()),
                        blankToNull(demandSubPersonaFilter.getValue()),
                        blankToNull(demandLocationFilter.getValue()),
                        blankToNull(demandClassificationStatusFilter.getValue()),
                        Boolean.TRUE.equals(demandActiveOnly.getValue())
                )
        );
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private Component buildDemandFlagChip(DemandDto dto) {
        if (!dto.masMappingMismatchFlag()) {
            return okChip();
        }
        return flagChip("red", "MAS mapping mismatch", dto.masMappingNote());
    }

    private Span okChip() {
        Span ok = new Span("—");
        ok.addClassName("bm-flag-chip-ok");
        return ok;
    }

    /**
     * A plain {@code Div} (not a {@code Button}) for the same reason {@code ReviewQueueView}'s Filters trigger uses one
     * — this needs its own pill shape/colors, and a Button would mean fighting Aura's own built-in button theming for
     * every variant (see the Override-button {@code !important} fix elsewhere in this app for what that costs).
     * <p>
     * 2026-10-08: no longer clickable — the Dialog-on-click detail view (with its own "Close" button) was replaced by a
     * plain hover {@link Tooltip}, since the full note text is short enough to just show on hover and a modal + button
     * was more interaction than the content warranted. Two follow-on fixes that come with dropping the click entirely:
     * (1) the trailing "▾" affordance (originally there to signal "this is clickable") is gone too — a hover-only chip
     * has nothing to click, so a click affordance would now be actively misleading; (2) Vaadin Grid still focuses a
     * cell on any mousedown inside it regardless of what's rendered there (that's the Grid's own keyboard-nav
     * bookkeeping, not something the chip's own listeners control), which read as "this cell is selected/active" for a
     * chip that does nothing on click any more — the mousedown listener below stops that event from reaching the Grid's
     * own handler, so clicking the chip no longer puts the cell into a focused state the way every other cell in the
     * row still does.
     * <p>
     * 2026-10-08 follow-up: {@code stopPropagation()} alone turned out not to be enough — it stops the event from
     * reaching other LISTENERS, but not the browser's own default action on {@code mousedown}, which is to shift native
     * DOM focus to the nearest focusable ancestor (the grid's own cell element) the first time the grid itself doesn't
     * already have focus. That native focus-shift happens independently of any listener, so it still showed the focus
     * ring on the very first click into an unfocused grid even with propagation stopped — but not on a second click,
     * once the grid already had focus from the first one (nothing left to "shift" to). Adding {@code preventDefault()}
     * suppresses that default action too, so the chip's own cell never grabs focus either way. Safe here specifically
     * because the chip is a plain, non-interactive {@code Div} with no click handler of its own any more (see above) —
     * {@code preventDefault()} on {@code mousedown} only cancels mousedown's own default actions (focus-shift,
     * text-selection-start), not the subsequent {@code click}, which this chip doesn't listen for anyway.
     */
    private Div flagChip(String colorVariant, String label, String note) {
        Span dot = new Span();
        dot.addClassName("bm-flag-chip-dot");
        Span text = new Span(label);

        Div chip = new Div(dot, text);
        chip.addClassNames("bm-flag-chip", "bm-flag-chip-" + colorVariant);
        Tooltip.forComponent(chip).setText(note != null ? note : "No further detail recorded for this flag.");
        chip.getElement().executeJs(
                "this.addEventListener('mousedown', function (e) { e.preventDefault(); e.stopPropagation(); });");
        return chip;
    }
}
