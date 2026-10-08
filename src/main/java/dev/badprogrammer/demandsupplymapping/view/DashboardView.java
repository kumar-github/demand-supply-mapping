package dev.badprogrammer.demandsupplymapping.view;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.signals.local.ValueSignal;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Landing page, restyled to match the approved {@code Main.dc.html} mock pixel-for-pixel (colors, radii, spacing, type
 * scale) rather than generic Aura chrome — see {@code styles.css}'s ".bm-dash-*" rules and {@link MainLayout}'s class
 * Javadoc for why.
 * <p>
 * The KPI values and the "needs attention" queue are real, computed in {@link DashboardMetrics}. One deliberate content
 * gap vs. the mock: the mock's 3rd tile is "Awaiting review" — 128/243 reviewed, 4 days left in cycle, an "OVERRIDE"
 * badge — all backed by a reviewer-workflow table (demand_review_state) that doesn't exist in this codebase yet. Rather
 * than fabricate that, the tile keeps the mock's exact visual treatment (amber border, amber value/caption) but shows
 * the one genuinely derivable signal instead: employees with no Strong/Good match in the latest run. The "OVERRIDE"
 * badge is dropped from queue rows for the same reason (no override entity yet); the "⚠ NO STRONG/GOOD" badge stays,
 * since that IS real.
 */
@Route(value = "dashboard", layout = MainLayout.class)
@RouteAlias(value = "", layout = MainLayout.class)
@PageTitle("Dashboard")
public class DashboardView extends Div {

    private static final DateTimeFormatter LAST_REFRESHED_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US);

    public DashboardView(DashboardMetrics metrics) {
        DashboardMetrics.Snapshot snapshot = metrics.load();

        addClassName("bm-dash");

        add(buildKpiRow(snapshot));

        Div middleRow = new Div(buildNeedsAttentionCard(snapshot), buildMatchQualityCard(snapshot));
        middleRow.addClassName("bm-dash-middle-row");
        add(middleRow);
    }

    private static String initialsOf(String name) {
        String[] parts = name.trim().split("\\s+");
        StringBuilder initials = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty() && initials.length() < 2) {
                initials.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return initials.isEmpty() ? "?" : initials.toString();
    }

    // A plain Div, not FlexLayout — FlexLayout's constructor hard-codes an INLINE
    // `display: flex` style on its element (confirmed in Vaadin's own source), which always
    // beats a stylesheet rule no matter its specificity. That silently defeated the
    // ".bm-kpi-row { display: grid }" rule last round — tiles fell back to flex's default
    // (size-to-content, packed left) instead of 4 equal, stretched columns. Div has no such
    // inline style, so the stylesheet's grid rule is free to apply.
    private Div buildKpiRow(DashboardMetrics.Snapshot snapshot) {
        Div row = new Div(
                kpiTile("Active bench", snapshot.activeBenchCount(), "Full Stack · Front End",
                        Kpi.PLAIN, "bm-kpi-caption-teal"),
                kpiTile("Active demand", snapshot.activeDemandCount(), "In Phase 1 scope", Kpi.PLAIN, null),
                kpiTile("Needs attention", snapshot.needsAttentionCount(),
                        "Employees with no Strong/Good match", Kpi.HIGHLIGHTED, null),
                kpiTile("Out of scope / unclassified", snapshot.outOfScopeOrUnclassifiedDemandCount(),
                        "Coverage log — coming soon", Kpi.MUTED, null)
        );
        row.addClassName("bm-kpi-row");
        return row;
    }

    private Div kpiTile(String label, int valueNumber, String caption, Kpi kind, String captionAccentClass) {
        Span labelSpan = new Span(label);
        labelSpan.addClassName("bm-kpi-label");

        // The numeric value is wired through a signal rather than set directly on the Span — a
        // small, genuine use of Flow 25's Signals API, per the agreed stack.
        ValueSignal<String> valueSignal = new ValueSignal<>(String.valueOf(valueNumber));
        Span value = new Span();
        value.bindText(valueSignal);
        value.addClassName("bm-kpi-value");

        Span captionSpan = new Span(caption);
        captionSpan.addClassName("bm-kpi-caption");
        if (captionAccentClass != null) {
            captionSpan.addClassName(captionAccentClass);
        }

        Div tile = new Div(labelSpan, value, captionSpan);
        tile.addClassName("bm-kpi-tile");
        switch (kind) {
            case HIGHLIGHTED -> tile.addClassName("bm-kpi-tile-highlighted");
            case MUTED -> tile.addClassName("bm-kpi-tile-muted");
            case PLAIN -> { /* no extra class */ }
        }
        return tile;
    }

    // Wording decided deliberately (not left over from the mock): "Needs attention" rather than
    // "Your queue" because there's no reviewer queue/ownership concept in this codebase yet — this
    // card is a flag list, not a workflow. The subtitle states the actual sort order (bench
    // ageing) rather than repeating "no Strong/Good match", which the badge on each row already
    // says — avoids saying the same thing twice in two different wordings.
    private Div buildNeedsAttentionCard(DashboardMetrics.Snapshot snapshot) {
        Span title = new Span("Needs attention");
        title.addClassName("bm-card-title-lg");
        Span subtitle = new Span("Longest-benched first");
        subtitle.addClassName("bm-card-subtitle");
        Div titleBlock = new Div(title, subtitle);
        titleBlock.addClassName("bm-card-title-block");

        // 2026-10-07: SupplyView.class → SupplyDemandView.class (Supply merged with Demand onto one
        // tabbed page); this link still lands on the Supply tab, same as before, since /supply is
        // SupplyDemandView's primary @Route.
        RouterLink reviewLink = new RouterLink("Review in Supply →", SupplyDemandView.class);
        reviewLink.addClassName("bm-pbtn");

        Div header = new Div(titleBlock, reviewLink);
        header.addClassName("bm-queue-header");

        Div rows = new Div();
        rows.addClassName("bm-queue-rows");
        for (DashboardMetrics.QueueRow row : snapshot.needsAttentionQueue()) {
            rows.add(buildQueueRow(row));
        }
        if (snapshot.needsAttentionQueue().isEmpty()) {
            Span empty = new Span("Nothing flagged right now.");
            empty.addClassName("bm-card-subtitle");
            rows.add(empty);
        }

        Div card = new Div(header, rows);
        card.addClassName("bm-card");

        int shown = snapshot.needsAttentionQueue().size();
        int remaining = snapshot.needsAttentionCount() - shown;
        if (remaining > 0) {
            Span more = new Span("+ " + remaining + " more, ordered the same way");
            more.addClassName("bm-queue-more");
            card.add(more);
        }

        Span reconciliation = new Span(snapshot.activeBenchCount() + " active bench = "
                + (snapshot.activeBenchCount() - snapshot.needsAttentionCount()) + " with a Strong/Good match + "
                + snapshot.needsAttentionCount() + " needing attention");
        reconciliation.addClassName("bm-queue-reconciliation");
        card.add(reconciliation);

        return card;
    }

//    private Div buildQueueRow(DashboardMetrics.QueueRow row) {
//        Div avatar = new Div(new Span(initialsOf(row.employeeName())));
//        avatar.addClassName("bm-queue-avatar");
//
//        Span name = new Span(row.employeeName());
//        name.addClassName("bm-queue-name");
//        Span meta = new Span(" · " + row.band() + " · " + row.location());
//        meta.addClassName("bm-queue-meta");
//        Div nameLine = new Div(name, meta);
//        nameLine.addClassName("bm-queue-name-line");
//
//        Span badge = new Span("⚠ NO STRONG/GOOD");
//        badge.addClassName("bm-badge-danger");
//
//        RouterLink open = new RouterLink("Review →", SupplyView.class);
//        open.addClassName("bm-queue-open");
//
//        Div queueRow = new Div(avatar, nameLine, badge, open);
//        queueRow.addClassName("bm-queue-row");
//        return queueRow;
//    }

    // Whole-row link, not a static row with a small nested link — matches the pattern Review's
    // own .bm-rev-row already uses (the whole card IS the RouterLink).
    private RouterLink buildQueueRow(DashboardMetrics.QueueRow row) {
        Div avatar = new Div(new Span(initialsOf(row.employeeName())));
        avatar.addClassName("bm-queue-avatar");

        Span name = new Span(row.employeeName());
        name.addClassName("bm-queue-name");
        Span meta = new Span(" · " + row.band() + " · " + row.location());
        meta.addClassName("bm-queue-meta");
        Div nameLine = new Div(name, meta);
        nameLine.addClassName("bm-queue-name-line");

        Span badge = new Span("⚠ NO STRONG/GOOD");
        badge.addClassName("bm-badge-danger");

        Span open = new Span("Review →");
        open.addClassName("bm-queue-open");   // now a plain label, not its own link

        // See buildNeedsAttentionCard's comment above — SupplyView.class → SupplyDemandView.class.
        RouterLink queueRow = new RouterLink(SupplyDemandView.class);
        queueRow.add(avatar, nameLine, badge, open);
        queueRow.addClassName("bm-queue-row");
        return queueRow;
    }

    private Div buildMatchQualityCard(DashboardMetrics.Snapshot snapshot) {
        Span title = new Span("Match quality across active bench");
        title.addClassName("bm-card-title");

        int rawTotal = snapshot.strongCount() + snapshot.goodCount() + snapshot.weakCount();
        int total = Math.max(1, rawTotal);
        Div bar = new Div();
        bar.addClassName("bm-quality-bar");
        if (rawTotal == 0) {
            // A flat, colorless bar with no caption reads as broken, not "no data yet" — say so
            // explicitly instead. Real cause: matching only runs via POST /api/matching/run (no
            // UI trigger exists), so this is empty until someone has called it at least once.
            bar.addClassName("bm-quality-bar-empty");
            Span emptyLabel = new Span("No matching run yet");
            emptyLabel.addClassName("bm-quality-bar-empty-label");
            bar.add(emptyLabel);
        } else {
            bar.add(qualitySegment("bm-quality-strong", snapshot.strongCount(), total));
            bar.add(qualitySegment("bm-quality-good", snapshot.goodCount(), total));
            bar.add(qualitySegment("bm-quality-weak", snapshot.weakCount(), total));
        }

        Div legend = new Div(
                legendItem("bm-quality-strong", "Strong · " + snapshot.strongCount()),
                legendItem("bm-quality-good", "Good · " + snapshot.goodCount()),
                legendItem("bm-quality-weak", "Weak · " + snapshot.weakCount())
        );
        legend.addClassName("bm-quality-legend");

        Div card = new Div(title, bar, legend);
        card.addClassName("bm-card");

        String refreshedText = snapshot.lastRefreshedAt() == null
                ? "No matching run yet"
                : "Last refreshed " + snapshot.lastRefreshedAt().format(LAST_REFRESHED_FORMAT);
        Span refreshed = new Span(refreshedText);
        refreshed.addClassName("bm-card-subtitle");
        card.add(refreshed);

        return card;
    }

    private Div qualitySegment(String className, int count, int total) {
        Div segment = new Div();
        segment.addClassNames("bm-quality-segment", className);
        double share = total == 0 ? 0 : (count * 100.0) / total;
        segment.getStyle().set("width", share + "%");
        return segment;
    }

    private Div legendItem(String swatchClass, String text) {
        Div swatch = new Div();
        swatch.addClassNames("bm-legend-swatch", swatchClass);
        Div item = new Div(swatch, new Span(text));
        item.addClassName("bm-legend-item");
        return item;
    }

    private enum Kpi {PLAIN, HIGHLIGHTED, MUTED}
}
