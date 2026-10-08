package dev.badprogrammer.demandsupplymapping.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldVariant;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.*;
import dev.badprogrammer.demandsupplymapping.review.DemandReviewService;
import dev.badprogrammer.demandsupplymapping.review.dto.DemandLifecycleState;
import dev.badprogrammer.demandsupplymapping.review.dto.ReviewQueueItemDto;
import dev.badprogrammer.demandsupplymapping.review.dto.ReviewQueueResult;

import java.util.*;

/**
 * Demand-side Review landing page — every actionable demand (≥1 Strong/Good candidate) that isn't fully Filled yet,
 * longest-overdue first (DemandReviewService.queue()'s Javadoc). Clicking a row opens {@link ReviewWorkspaceView} for
 * that one demand.
 * <p>
 * Rebuilt 2026-10-05 to match the approved wireframe (Main.dc.html, "Demand Queue (landing)" artboard) structurally,
 * not just a Dashboard-style card list: urgency-colored left-border rows with a dot+label badge, header stat line,
 * legend, a real search box, and classic page-based pagination (20 rows/page, Prev/1/2/3/Next) — not infinite scroll or
 * Grid virtualization. A same-day earlier attempt used a Vaadin {@code Grid} for virtualized rendering once the row
 * count turned out to be in the hundreds (458 in the reporter's dataset); that solved the "very long list to scroll"
 * complaint mechanically but didn't match the mock's actual design, which never intended infinite scroll at all —
 * paging 20 at a time is both the correct UX per the mock AND still only renders ~20 rows of real DOM at a time, so
 * there's no virtualization need left once paging is done properly. {@code DemandReviewService.queue()} stays a plain
 * in-memory {@code List<ReviewQueueItemDto>}; paging/search/urgency grouping all happen here, client-side, over that
 * list — fine at this row count (hundreds, not thousands), same reasoning that list's own Javadoc already gives for not
 * needing a Pageable-aware counterpart.
 * <p>
 * 2026-10-06: the mock's Persona/Band/Location filter chips and "Sort: Urgency ▾" dropdown were flagged as decorative
 * (present visually, not wired to anything) in that same UI/UX pass. Wired up here rather than left as-is or dropped,
 * since the backing data (persona/band/location on {@link ReviewQueueItemDto}) was already available and the
 * filtering/sorting itself is cheap at this row count — same "build it real, don't fabricate or silently drop it" call
 * already made elsewhere in this app (see {@code DashboardView}'s and {@code MainLayout}'s own mock-gap comments). The
 * "N have zero candidate" stat from that same pass is the one exception: it stays a plain count, not a link, because
 * its only real target (a Coverage Log view) genuinely doesn't exist yet — see
 * {@code DemandReviewService.noCoverageCount()}'s Javadoc.
 */
@Route(value = "review", layout = MainLayout.class)
@PageTitle("Review")
public class ReviewQueueView extends VerticalLayout implements BeforeEnterObserver {

    private static final int PAGE_SIZE = 20;
    private static final String SORT_URGENCY = "Most urgent first";
    private static final String SORT_POSITIONS = "Most open positions first";
    private static final String SORT_CANDIDATES = "Fewest candidates first";
    private static final String SORT_CUSTOMER = "Customer A–Z";
    private final DemandReviewService reviewService;
    private final List<ReviewQueueItemDto> queue;
    private final Set<String> flaggedDemandIds;
    private final TextField search = new TextField();
    private final Checkbox flaggedOnly = new Checkbox("Flagged for hiring only");
    private final Select<String> sortSelect = new Select<>();
    private final Div rowsContainer = new Div();
    private final Div paginationContainer = new Div();

    // Multi-select-within-group, AND-across-groups chip filters (Persona/Band/Location) — see
    // styles.css's .bm-rev-filters-panel comment for why these exist now instead of the mock's
    // static decoration. Populated from the actual queue's distinct values in
    // refreshFilterChipGroups(), not a fixed list, so a chip never offers a value that would
    // return zero rows.
    private final Set<String> personaFilters = new LinkedHashSet<>();
    private final Set<String> bandFilters = new LinkedHashSet<>();
    private final Set<String> locationFilters = new LinkedHashSet<>();
    private final int noCoverageCount;
    // 2026-10-07 header-declutter decision — see styles.css's .bm-rev-filters-panel comment for
    // the full before/after reasoning (mocked up as "Idea A" against a per-facet-dropdown
    // alternative before landing here). Persona/Band/Location move out of three permanently-open
    // chip rows and into one collapsible "Filters" popover; search/flagged-only/Filters/sort all
    // join one toolbar row instead of two. Location stays a plain chip list for now, same as
    // Persona/Band — a searchable multi-select was mocked up too but deliberately deferred until
    // the number of distinct locations actually grows enough to matter.
    private final Div filtersPanel = new Div();
    private final Div filterChipGroups = new Div();
    private final Span filtersCountBadge = new Span();
    private Span flaggedCountSpan;
    private List<ReviewQueueItemDto> filtered;
    private int page = 0;
    private boolean filtersOpen = false;

    public ReviewQueueView(DemandReviewService reviewService) {
        this.reviewService = reviewService;
        // One combined call instead of queue() + noCoverageCount() separately — each is its own
        // full match_candidates.findAll() scan, and this view needs both every single time it's
        // constructed (which Vaadin does on every navigation back to /review, not just once) — a
        // real, doubled cost flagged 2026-10-06. See ReviewQueueResult's Javadoc.
        ReviewQueueResult result = reviewService.queueAndCoverage();
        this.queue = result.items();
        this.noCoverageCount = result.noCoverageCount();
        this.filtered = queue;
        this.flaggedDemandIds = new HashSet<>();
        for (ReviewQueueItemDto item : queue) {
            if (item.flaggedForHiring()) {
                flaggedDemandIds.add(item.demandId());
            }
        }

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(buildHeader());
        add(buildToolbarRow());

        rowsContainer.addClassName("bm-rev-rows");
        add(rowsContainer);
        setFlexGrow(1, rowsContainer);

        add(buildNote());

        paginationContainer.setWidthFull();
        add(paginationContainer);
    }

    /**
     * Reads the "page" query param (1-indexed in the URL, matching the visible page-number buttons) so a reviewer who
     * opens a demand from, say, page 6 and comes back via {@link ReviewWorkspaceView}'s "Back to queue" link lands on
     * page 6 again instead of being dumped back to page 1 — a real workflow cost at 247+ demands that a 2026-10-05
     * UI/UX pass flagged and this fixes. Runs before the view is shown, so {@code render()} only happens here, not in
     * the constructor (query params aren't known yet at construction time).
     */
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        List<String> pageParam = event.getLocation().getQueryParameters().getParameters().get("page");
        if (pageParam != null && !pageParam.isEmpty()) {
            try {
                page = Math.max(0, Integer.parseInt(pageParam.get(0)) - 1);
            } catch (NumberFormatException ignored) {
                page = 0;
            }
        }
        render();
    }

    private Div buildHeader() {
        Span title = new Span("Demand Review");
        title.addClassName("bm-card-title-lg");

        int overdue = (int) queue.stream().filter(i -> i.ageingRank() == 0).count();
        int dueSoon = (int) queue.stream().filter(i -> i.ageingRank() == 1).count();

        Span totalCount = new Span(String.valueOf(queue.size()));
        totalCount.getStyle().set("font-weight", "700").set("color", "var(--bm-ink)").set("font-size", "15px");
        Span total = new Span(totalCount, new Span(" demand(s) need review"));
        Span overdueSpan = new Span(overdue + " overdue");
        overdueSpan.addClassName("bm-rev-stat-overdue");
        Span dueSoonSpan = new Span(dueSoon + " due this week");
        dueSoonSpan.addClassName("bm-rev-stat-duesoon");
        Span noCoverageSpan = new Span(noCoverageCount + " have zero candidate");
        noCoverageSpan.addClassName("bm-rev-stat-nocoverage");

        // Reads flaggedDemandIds (not item.flaggedForHiring()) so a flag clicked earlier in this
        // same page view — before any reload — is reflected immediately, same reasoning the
        // "Flagged only" checkbox below and the row badges use.
        flaggedCountSpan = new Span(flaggedDemandIds.size() + " flagged for hiring");
        flaggedCountSpan.addClassName("bm-rev-stat-flagged");

        Div stats = new Div(total, overdueSpan, dueSoonSpan, noCoverageSpan, flaggedCountSpan);
        stats.addClassName("bm-rev-stats");

        // 2026-10-07: folded onto the same line as the stats instead of its own row below the
        // toolbar — it was a second, separate row that only restated the three urgency states the
        // stats line (and every row's own colored dot) already carry, just in a different visual
        // language (dots vs. colored text). margin-left: auto pushes it to the line's right edge
        // when there's room and lets it wrap below on narrow widths, same as the other stats do.
        Div legend = buildLegend();
        legend.getStyle().set("margin-left", "auto");
        stats.add(legend);

        Div header = new Div(title, stats);
        header.addClassName("bm-rev-header");
        return header;
    }

    private void refreshFlaggedCount() {
        flaggedCountSpan.setText(flaggedDemandIds.size() + " flagged for hiring");
    }

    /**
     * Search, "Flagged only", the Filters popover trigger, and the sort dropdown — one toolbar row instead of the two
     * the mock originally had (a search row, then a separate chips+sort row). See the class-level 2026-10-07 field
     * comment for why.
     */
    private Div buildToolbarRow() {
        search.setPlaceholder("Search demand ID, customer, project…");
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.addThemeVariants(TextFieldVariant.SMALL);
        search.addClassName("bm-rev-search");
        search.addValueChangeListener(e -> {
            page = 0;
            applyFilters();
            render();
        });

        flaggedOnly.addClassName("bm-rev-flagged-filter");
        flaggedOnly.addValueChangeListener(e -> {
            page = 0;
            applyFilters();
            render();
        });

        sortSelect.setItems(SORT_URGENCY, SORT_POSITIONS, SORT_CANDIDATES, SORT_CUSTOMER);
        sortSelect.setValue(SORT_URGENCY);
        sortSelect.addClassName("bm-rev-sort");
        sortSelect.addValueChangeListener(e -> {
            page = 0;
            applyFilters();
            render();
        });

        Div toolbar = new Div(search, flaggedOnly, buildFiltersControl(), sortSelect);
        toolbar.addClassName("bm-rev-toolbar-row");
        return toolbar;
    }

    /**
     * Persona/Band/Location filter chips, built from the actual queue data (not a static mock copy) — see this class's
     * field-level comment and styles.css's .bm-rev-filters-panel comment for why these were wired up rather than left
     * decorative, and why they now live in a collapsed-by-default popover instead of three permanently-open rows.
     */
    private Div buildFiltersControl() {
        filterChipGroups.addClassName("bm-rev-filters-panel-groups");
        refreshFilterChipGroups();

        Button clearAll = new Button("Clear all", e -> {
            personaFilters.clear();
            bandFilters.clear();
            locationFilters.clear();
            page = 0;
            applyFilters();
            render();
            refreshFilterChipGroups();
            refreshFiltersCount();
        });
        clearAll.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);

        Button done = new Button("Done", e -> {
            filtersOpen = false;
            filtersPanel.setVisible(false);
        });
        done.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.PRIMARY);

        Div actions = new Div(clearAll, done);
        actions.addClassName("bm-rev-filters-panel-actions");

        filtersPanel.add(filterChipGroups, actions);
        filtersPanel.addClassName("bm-rev-filters-panel");
        filtersPanel.setVisible(false);

        filtersCountBadge.addClassName("bm-rev-filters-count");
        refreshFiltersCount();

        // Div, not Button — this codebase already uses a clickable Div for exactly this (see
        // ReviewWorkspaceView.buildCandidateCard()'s nameLine), and it sidesteps having to compose
        // a label + trailing count badge inside a single Button.
        Div filtersBtn = new Div(new Span("Filters"), filtersCountBadge, new Span("▾"));
        filtersBtn.addClassName("bm-rev-filters-btn");
        filtersBtn.addClickListener(e -> {
            filtersOpen = !filtersOpen;
            filtersPanel.setVisible(filtersOpen);
        });

        Div wrap = new Div(filtersBtn, filtersPanel);
        wrap.addClassName("bm-rev-filters-wrap");

        // 2026-10-08 fix: the popover used to only close via the "Filters" toggle or "Done" — no
        // outside-click or Esc handling existed at all. Esc is handled server-side via Vaadin's own
        // Shortcuts API (no JS needed). Outside-click has no Flow-native equivalent, so a document
        // 'click' listener is attached in JS once this element is attached to the page; it checks
        // whether the click landed inside `wrap` (covers both the button and the panel, so clicking
        // the Filters button itself to toggle isn't mistaken for an "outside" click) and, if not,
        // dispatches a plain DOM CustomEvent that a normal Flow DomListener picks back up server-side
        // — avoids a bespoke @ClientCallable just to get one boolean back. The document listener is
        // removed on detach so repeat visits to /review (a fresh ReviewQueueView each time) don't
        // pile up duplicate handlers on the one shared `document`.
        wrap.addAttachListener(attachEvent -> wrap.getElement().executeJs(
                "const wrap = this;"
                        + "const handler = function(e) { if (!wrap.contains(e.target)) { wrap.dispatchEvent(new CustomEvent('bm-filters-close')); } };"
                        + "document.addEventListener('click', handler);"
                        + "wrap.__bmFiltersCloseHandler = handler;"));
        wrap.addDetachListener(detachEvent -> wrap.getElement().executeJs(
                "if (this.__bmFiltersCloseHandler) { document.removeEventListener('click', this.__bmFiltersCloseHandler); this.__bmFiltersCloseHandler = null; }"));
        wrap.getElement().addEventListener("bm-filters-close", e -> closeFiltersPopover());

        Shortcuts.addShortcutListener(this, this::closeFiltersPopover, Key.ESCAPE);

        return wrap;
    }

    private void closeFiltersPopover() {
        if (filtersOpen) {
            filtersOpen = false;
            filtersPanel.setVisible(false);
        }
    }

    // Full rebuild of the three chip groups — needed after "Clear all" (several chips' active
    // state change at once) and on first build. An individual chip click still does its own
    // cheap in-place class toggle (see chipGroup()'s listener) rather than going through here.
    private void refreshFilterChipGroups() {
        filterChipGroups.removeAll();
        filterChipGroups.add(
                chipGroup("Persona", distinctValues(ReviewQueueItemDto::persona), personaFilters),
                chipGroup("Band", distinctValues(ReviewQueueItemDto::band), bandFilters),
                chipGroup("Location", distinctValues(ReviewQueueItemDto::location), locationFilters)
        );
    }

    private void refreshFiltersCount() {
        int active = personaFilters.size() + bandFilters.size() + locationFilters.size();
        filtersCountBadge.setText(String.valueOf(active));
        filtersCountBadge.setVisible(active > 0);
    }

    // TreeSet for a stable, alphabetical chip order regardless of queue order; nulls/blanks
    // dropped rather than offered as a filterable "—" value, same as the row text already does.
    private Set<String> distinctValues(java.util.function.Function<ReviewQueueItemDto, String> extractor) {
        Set<String> values = new TreeSet<>();
        for (ReviewQueueItemDto item : queue) {
            String v = extractor.apply(item);
            if (v != null && !v.isBlank()) {
                values.add(v);
            }
        }
        return values;
    }

    private Div chipGroup(String label, Set<String> values, Set<String> activeFilters) {
        Div group = new Div();
        group.addClassName("bm-rev-chip-group");
        if (values.isEmpty()) {
            return group;
        }
        Span groupLabel = new Span(label);
        groupLabel.addClassName("bm-rev-chip-group-label");
        group.add(groupLabel);
        for (String value : values) {
            Span chip = new Span(value);
            chip.addClassName("bm-rev-chip");
            boolean active = activeFilters.contains(value);
            if (active) {
                chip.addClassName("bm-rev-chip--active");
            }
            chip.addClickListener(e -> {
                // 2026-10-07 fix: this used to flip classes off the `active` boolean captured when
                // the chip was BUILT, not the Set's state at CLICK time — so after the toggle below,
                // `!active` kept evaluating to the same frozen value on every subsequent click,
                // and the chip's highlight only ever turned on once, never back off. Re-reading
                // activeFilters.contains(value) after the toggle (i.e. the real post-click state)
                // instead of relying on the stale local is what actually fixes it.
                boolean nowActive;
                if (activeFilters.contains(value)) {
                    activeFilters.remove(value);
                    nowActive = false;
                } else {
                    activeFilters.add(value);
                    nowActive = true;
                }
                page = 0;
                applyFilters();
                render();
                // Full header/toolbar/filters-panel rebuild would also work, but is unnecessary
                // here: only this one chip's active state changed, and re-rendering just it avoids
                // losing focus/scroll position on every click the way a full add()-replace would.
                chip.setClassName("bm-rev-chip", true);
                chip.setClassName("bm-rev-chip--active", nowActive);
                refreshFiltersCount();
            });
            group.add(chip);
        }
        return group;
    }

    private Div buildLegend() {
        Div legend = new Div(
                legendItem("var(--bm-rev-overdue)", "Overdue"),
                legendItem("var(--bm-rev-duesoon)", "Due soon"),
                legendItem("var(--bm-rev-ontrack)", "On track")
        );
        legend.addClassName("bm-rev-legend");
        return legend;
    }

    private Div legendItem(String color, String label) {
        Span dot = new Span();
        dot.addClassName("bm-rev-legend-dot");
        dot.getStyle().set("background", color);
        Div item = new Div(dot, new Span(label));
        item.addClassName("bm-rev-legend-item");
        return item;
    }

    private Div buildNote() {
        Span dot = new Span();
        dot.addClassName("bm-rev-note-dot");
        Span text = new Span("Exhausted = every Strong/Good candidate already Proposed or Rejected, "
                + "but positions remain open — this is the live signal for proactive hiring, not a stuck queue item.");
        Div note = new Div(dot, text);
        note.addClassName("bm-rev-note");
        return note;
    }

    private void applyFilters() {
        String t = search.getValue() == null ? "" : search.getValue().trim().toLowerCase();
        boolean onlyFlagged = flaggedOnly.getValue();

        List<ReviewQueueItemDto> result = queue.stream()
                .filter(i -> t.isEmpty() || containsIgnoreCase(i.demandId(), t)
                        || containsIgnoreCase(i.customer(), t)
                        || containsIgnoreCase(i.projectName(), t))
                .filter(i -> !onlyFlagged || flaggedDemandIds.contains(i.demandId()))
                .filter(i -> personaFilters.isEmpty() || personaFilters.contains(i.persona()))
                .filter(i -> bandFilters.isEmpty() || bandFilters.contains(i.band()))
                .filter(i -> locationFilters.isEmpty() || locationFilters.contains(i.location()))
                .toList();

        Comparator<ReviewQueueItemDto> sort = sortComparator();
        filtered = sort == null ? result : result.stream().sorted(sort).toList();
    }

    // null = keep the queue's own order (longest-overdue-first, DemandReviewService.queue()'s
    // default) — the filter/stream calls above are already order-preserving, so there's nothing
    // to re-sort for the default case; every other option re-sorts the already-filtered list.
    private Comparator<ReviewQueueItemDto> sortComparator() {
        String choice = sortSelect.getValue();
        if (choice == null || SORT_URGENCY.equals(choice)) {
            return null;
        }
        if (SORT_POSITIONS.equals(choice)) {
            return Comparator.comparingInt((ReviewQueueItemDto i) ->
                    i.balancePositions() == null ? 0 : i.balancePositions()).reversed();
        }
        if (SORT_CANDIDATES.equals(choice)) {
            return Comparator.comparingInt(i -> i.strongCount() + i.goodCount());
        }
        if (SORT_CUSTOMER.equals(choice)) {
            return Comparator.comparing(i -> i.customer() == null ? "" : i.customer().toLowerCase());
        }
        return null;
    }

    private boolean containsIgnoreCase(String value, String term) {
        return value != null && value.toLowerCase().contains(term);
    }

    private void render() {
        render(false);
    }

    /**
     * 2026-10-07: {@code animateRows} is true only from the pagination buttons' click listener. A reviewer asked for
     * *something* to signal "new data loaded" when paging, short of a full scroll animation (discussed and deliberately
     * rejected — in a side-by-side mock, scrolling alone read as "the page just scrolled", not "the content changed"; a
     * fade felt like the latter). Scoped to paging only, not filter/search/sort, since those already have their own
     * visible trigger (the control the reviewer just touched) and don't need a second cue.
     */
    private void render(boolean animateRows) {
        rowsContainer.removeAll();

        if (filtered.isEmpty()) {
            Span empty = new Span(queue.isEmpty() ? "Nothing to review right now." : "No demands match your search.");
            empty.addClassName("bm-card-subtitle");
            rowsContainer.add(empty);
            paginationContainer.removeAll();
            return;
        }

        int totalPages = (int) Math.ceil(filtered.size() / (double) PAGE_SIZE);
        if (page >= totalPages) {
            page = totalPages - 1;
        }
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, filtered.size());

        List<ReviewQueueItemDto> pageItems = filtered.subList(from, to);
        for (ReviewQueueItemDto item : pageItems) {
            Component row = buildRow(item);
            if (animateRows) {
                row.getElement().getClassList().add("bm-rev-row-enter");
            }
            rowsContainer.add(row);
        }

        if (animateRows) {
            animateRowsIn();
        }

        renderPagination(from, to, totalPages);
    }

    // Adds "enter-active" one tick after the rows were added with "enter" already on them, staggered
    // slightly per row — same two-class technique as a CSS transition requires (the browser needs a
    // layout/paint with the starting state applied before the transition-triggering class lands, or
    // it just snaps to the end state with no visible motion). Done from here in one executeJs call on
    // the container rather than per-row, so it's one round trip, not N.
    private void animateRowsIn() {
        rowsContainer.getElement().executeJs(
                "var rows = this.querySelectorAll('.bm-rev-row-enter');"
                        + "rows.forEach(function (row, i) {"
                        + "  setTimeout(function () {"
                        + "    row.classList.add('bm-rev-row-enter-active');"
                        + "    row.classList.remove('bm-rev-row-enter');"
                        + "  }, 20 + i * 18);"
                        + "});");
    }

    private void renderPagination(int from, int to, int totalPages) {
        paginationContainer.removeAll();

        String sortLabel = sortSelect.getValue() == null ? SORT_URGENCY : sortSelect.getValue();
        Span caption = new Span("Showing " + (from + 1) + "–" + to + " of " + filtered.size()
                + ", sorted by " + sortLabel.substring(0, 1).toLowerCase() + sortLabel.substring(1));
        caption.addClassName("bm-rev-pagination-caption");

        Div pages = new Div();
        pages.addClassName("bm-rev-pagination-pages");

        pages.add(pageButton("Prev", page - 1, page > 0));
        // Compact page-number window around the current page, capped at 5 buttons, same idea as
        // GridPagingUtil's own pager — this list only needs the same treatment client-side here.
        int windowStart = Math.max(0, Math.min(page - 2, totalPages - 5));
        int windowEnd = Math.min(totalPages, windowStart + 5);
        for (int p = windowStart; p < windowEnd; p++) {
            Button btn = pageButton(String.valueOf(p + 1), p, true);
            if (p == page) {
                btn.addClassName("bm-rev-page-btn--active");
            }
            pages.add(btn);
        }
        pages.add(pageButton("Next", page + 1, page < totalPages - 1));

        Div pagination = new Div(caption, pages);
        pagination.addClassName("bm-rev-pagination");
        paginationContainer.removeAll();
        paginationContainer.add(pagination);
    }

    private Button pageButton(String label, int targetPage, boolean enabled) {
        Button btn = new Button(label);
        btn.addClassName("bm-rev-page-btn");
        btn.addThemeVariants(ButtonVariant.TERTIARY);
        btn.setEnabled(enabled);
        btn.addClickListener(e -> {
            // 2026-10-08 fix: clicking the CURRENT page's own number button used to still run the
            // full render(true) — re-rendering every row with the "new page loaded" fade animation
            // even though nothing changed, which read as misleading (looked like the data had
            // actually been reloaded). Nothing to do when the target is already the active page.
            if (targetPage == page) {
                return;
            }
            page = targetPage;
            render(true);
            // Keep the address bar in sync with the in-memory page — same instance, no reload
            // (Vaadin reuses the current view for a query-param-only navigation to its own
            // route), but now the page survives a browser refresh/bookmark/back-button too, not
            // just a round trip through ReviewWorkspaceView's "Back to queue" link.
            UI.getCurrent().getPage().getHistory().replaceState(null,
                    "review?page=" + (targetPage + 1));
            // Fix 2026-10-07: render() swaps this view's rows in place — it doesn't move the
            // scroll position at all. The actual scrolling element is MainLayout's .bm-outlet
            // (the shell pins header/sidebar at 100vh with overflow: hidden and lets only the
            // outlet scroll internally — see styles.css's .bm-shell comment), not the browser
            // window, so a plain window.scrollTo(0, 0) would be a no-op here. Without this, a
            // reviewer who pages forward from the bottom of a long list lands on the new page
            // still scrolled to the bottom, with no visual cue the page actually changed.
            getElement().executeJs(
                    "var o = this.closest('.bm-outlet'); if (o) { o.scrollTop = 0; }");
        });
        return btn;
    }

    private Component buildRow(ReviewQueueItemDto item) {
        boolean exhausted = item.lifecycleState() == DemandLifecycleState.EXHAUSTED;

        Div urgency = new Div();
        urgency.addClassName("bm-rev-urgency");
        Span urgencyLabel = new Span(urgencyLabelText(item, exhausted));
        urgencyLabel.addClassName("bm-rev-urgency-label");
        if (exhausted) {
            urgencyLabel.addClassName("bm-rev-urgency-label--exhausted");
        } else {
            String modifier = urgencyClass(item.ageingRank());
            if (!modifier.isEmpty()) {
                urgencyLabel.addClassName(modifier);
            }
        }
        Span demandId = new Span(item.demandId());
        demandId.addClassName("bm-rev-id");
        urgency.add(urgencyLabel, demandId);
        // A demand flagged while EXHAUSTED can later re-enter OPEN (a fresh matching run finds a
        // new Strong/Good candidate) — the flag itself outlives that state change (it's not
        // cleared anywhere), so this badge has to be checked on every row, not just the
        // EXHAUSTED-only actions in buildExhaustedActions(), or the flag would silently vanish
        // from view the moment the demand becomes actionable again.
        if (flaggedDemandIds.contains(item.demandId())) {
            Span flaggedBadge = new Span("🚩 Flagged");
            flaggedBadge.addClassName("bm-rev-flagged-badge");
            urgency.add(flaggedBadge);
        }

        Div info = new Div();
        info.addClassName("bm-rev-info");
        Span infoTitle = new Span(classificationLine(item));
        infoTitle.addClassName("bm-rev-title");
        Span infoSub = new Span(customerLine(item));
        infoSub.addClassName("bm-rev-sub");
        info.add(infoTitle, infoSub);

        int positions = item.balancePositions() == null ? 0 : item.balancePositions();
        Div positionsBlock = new Div();
        positionsBlock.addClassName("bm-rev-positions");
        Span positionsMain = new Span(positions + (positions == 1 ? " position" : " positions"));
        positionsMain.addClassName("bm-rev-positions-main");
        Span positionsSub = new Span(item.approvedCount() + " of " + positions + " filled"
                + (exhausted ? " — no one left to decide on" : ""));
        positionsSub.addClassName(exhausted ? "bm-rev-positions-sub bm-rev-positions-sub--warn" : "bm-rev-positions-sub");
        positionsBlock.add(positionsMain, positionsSub);

        Div pills = new Div();
        pills.addClassName("bm-rev-pills");
        if (exhausted) {
            Span decided = new Span("All candidates decided");
            decided.addClassName("bm-rev-pill");
            decided.addClassName("bm-rev-pill-zero");
            pills.add(decided);
        } else {
            Span strong = new Span(item.strongCount() + " Strong");
            strong.addClassName("bm-rev-pill");
            strong.addClassName(item.strongCount() > 0 ? "bm-rev-pill-strong" : "bm-rev-pill-zero");
            Span good = new Span(item.goodCount() + " Good");
            good.addClassName("bm-rev-pill");
            good.addClassName(item.goodCount() > 0 ? "bm-rev-pill-good" : "bm-rev-pill-zero");
            pills.add(strong, good);
        }

        if (exhausted) {
            // Deliberately NOT a single whole-row RouterLink here, unlike the OPEN/non-exhausted
            // case below. "Flag for hiring" needs to be a real, separately-clickable action (it
            // persists demand_review_state.flagged_for_hiring — see DemandReviewService.
            // flagForHiring()'s Javadoc) rather than just more text glued onto a card that
            // navigates on any click; nesting an actionable <button> inside an <a> to get that
            // for free is invalid HTML and unreliable with Vaadin's client-side router
            // intercepting the anchor click first. So this row is a plain Div with two distinct,
            // genuinely different actions: "View" (still opens the read-only workspace — useful
            // to see who was proposed/rejected and when) and the flag action itself.
            Div row = new Div(urgency, info, positionsBlock, pills, buildExhaustedActions(item));
            row.addClassName("bm-rev-row");
            row.addClassName("bm-rev-row--exhausted");
            return row;
        }

        Span ctaText = new Span("Review");
        Span arrow = new Span("→");
        Div cta = new Div(ctaText, arrow);
        cta.addClassName("bm-rev-cta");

        // Text-plus-components constructor, same overload ReviewQueueView already relied on before
        // this rewrite ("Open →") — a RouterLink that also carries a navigation parameter has no
        // no-text overload, so an empty-string text node is unavoidable; it renders as nothing.
        RouterLink row = new RouterLink("", ReviewWorkspaceView.class, item.demandId());
        row.addClassName("bm-rev-row");
        row.addClassName(rowUrgencyClass(item));
        row.add(urgency, info, positionsBlock, pills, cta);
        // Carries the current page back through the workspace (see beforeEnter()'s Javadoc) so
        // "Back to queue"/auto-advance-to-queue lands here again instead of resetting to page 1.
        row.setQueryParameters(QueryParameters.simple(Map.of("page", String.valueOf(page + 1))));
        return row;
    }

    private Div buildExhaustedActions(ReviewQueueItemDto item) {
        RouterLink view = new RouterLink("View", ReviewWorkspaceView.class, item.demandId());
        view.addClassName("bm-rev-cta");
        view.addClassName("bm-rev-cta--muted");
        view.setQueryParameters(QueryParameters.simple(Map.of("page", String.valueOf(page + 1))));

        boolean flagged = flaggedDemandIds.contains(item.demandId());
        Button flagButton = new Button(flagged ? "Flagged ✓" : "Flag for hiring");
        flagButton.addClassName("bm-rev-flag-btn");
        if (flagged) {
            flagButton.addClassName("bm-rev-flag-btn--done");
        }
        flagButton.setEnabled(!flagged);
        flagButton.addClickListener(e -> {
            reviewService.flagForHiring(item.demandId());
            flaggedDemandIds.add(item.demandId());
            flagButton.setText("Flagged ✓");
            flagButton.addClassName("bm-rev-flag-btn--done");
            flagButton.setEnabled(false);
            refreshFlaggedCount();
        });

        Div actions = new Div(view, flagButton);
        actions.addClassName("bm-rev-exhausted-actions");
        return actions;
    }

    // Only called for non-exhausted rows now — EXHAUSTED gets its urgency class applied directly
    // in buildRow(), since that branch is a plain Div, not this method's RouterLink caller.
    private String rowUrgencyClass(ReviewQueueItemDto item) {
        return switch (item.ageingRank()) {
            case 0 -> "bm-rev-row--overdue";
            case 1 -> "bm-rev-row--duesoon";
            default -> "bm-rev-row--ontrack";
        };
    }

    private String urgencyLabelText(ReviewQueueItemDto item, boolean exhausted) {
        if (exhausted) {
            return "● EXHAUSTED";
        }
        String raw = item.newAgeing() != null && !item.newAgeing().isBlank()
                ? item.newAgeing()
                : item.dueCategory();
        if (raw == null || raw.isBlank()) {
            raw = item.ageingRank() == 0 ? "Overdue" : item.ageingRank() == 1 ? "Due soon" : "On track";
        }
        return "● " + raw.toUpperCase();
    }

    private String urgencyClass(int rank) {
        return rank == 0 ? "bm-rev-urgency-label--overdue" : rank == 1 ? "bm-rev-urgency-label--duesoon" : "";
    }

    private String classificationLine(ReviewQueueItemDto item) {
        StringBuilder sb = new StringBuilder();
        sb.append(label(item));
        if (item.band() != null && !item.band().isBlank()) {
            sb.append(" · ").append(item.band());
        }
        if (item.location() != null && !item.location().isBlank()) {
            sb.append(" · ").append(item.location());
        }
        return sb.toString();
    }

    private String customerLine(ReviewQueueItemDto item) {
        String customer = item.customer() == null || item.customer().isBlank() ? "—" : item.customer();
        String project = item.projectName() == null || item.projectName().isBlank() ? null : item.projectName();
        return project == null ? customer : (customer + " — " + project);
    }

    private String label(ReviewQueueItemDto item) {
        if (item.subPersona() != null) {
            return item.persona() + " - " + item.subPersona();
        }
        return item.persona() == null ? (item.clusterNameRaw() == null ? "—" : item.clusterNameRaw()) : item.persona();
    }
}
