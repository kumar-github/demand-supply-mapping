package dev.badprogrammer.demandsupplymapping.view;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.*;
import dev.badprogrammer.demandsupplymapping.entity.DemandCandidateDecision;
import dev.badprogrammer.demandsupplymapping.review.DemandReviewService;
import dev.badprogrammer.demandsupplymapping.review.dto.DecisionRequest;
import dev.badprogrammer.demandsupplymapping.review.dto.DemandLifecycleState;
import dev.badprogrammer.demandsupplymapping.review.dto.ReviewCandidateDto;
import dev.badprogrammer.demandsupplymapping.review.dto.ReviewWorkspaceDto;

import java.util.*;

/**
 * Demand Workspace — the per-demand decision screen opened from {@link ReviewQueueView}. Shows the demand's own ask
 * (skill cluster, location/band, customer/project, due category) plus every Strong/Good/Weak candidate, grouped by
 * tier, each with Propose/Reject.
 * <p>
 * UX decisions locked in 2026-10-05 (all "Recommended" in the AskUserQuestion round):
 * <ul>
 *   <li>Decided candidates stay visible, grayed out — never removed from the list.</li>
 *   <li>Auto-advances to the next demand in the queue once the LAST undecided Strong/Good
 *       candidate on this demand is decided (i.e. the demand leaves OPEN for FILLED/EXHAUSTED).</li>
 *   <li>Mouse-only — no keyboard shortcuts in this first build.</li>
 * </ul>
 * Rebuilds its content wholesale after every decision rather than patching the DOM in place —
 * simplest correct option for a screen that's clicked through at most a few hundred times a day,
 * and keeps this class readable; revisit only if that volume assumption changes.
 */
@Route(value = "review/demand", layout = MainLayout.class)
@PageTitle("Review")
public class ReviewWorkspaceView extends Div implements HasUrlParameter<String> {

    /**
     * Weak is shown at most once per demand (2026-10-07 decision) — Strong and Good stay uncapped, but Weak is a
     * last-resort signal ("there's at least one option if nothing else works out"), not a tier a reviewer needs to
     * review in full. Matches the spirit of {@code MatchingService.capEmployeeRows()}'s export-side rule (Weak only
     * matters once Strong/Good run out) without copying it exactly — here Weak is capped unconditionally rather than
     * suppressed only when Strong/Good are both empty, since the decision was "always show one," not "show it only as a
     * fallback."
     */
    private static final int WEAK_DISPLAY_LIMIT = 1;
    /**
     * Override-eligible is capped the same way, for the same display-noise reason (2026-10-07 decision) — NOT ranked
     * the way Good's top-3 is in the export (ageing rank / balance positions), because those are per-DEMAND attributes
     * and this workspace is already scoped to one demand — every override-eligible row here shares the same demand, so
     * there's nothing to rank by on that axis. Deliberately just the first N in whatever order the candidate list
     * already comes back in (the same, currently-unordered, way Strong/Good/Weak are shown within their own tier)
     * rather than inventing new per-employee ranking logic this view doesn't have the data for.
     */
    private static final int OVERRIDE_DISPLAY_LIMIT = 5;
    private final DemandReviewService reviewService;
    /**
     * Per-card expand state ("two states, four dimensions" — collapsed by default, expanded on click), independent of
     * {@link #expandAll}. Keyed by employeeId since a workspace only ever shows one demand's candidates at a time.
     */
    private final Set<Long> expandedEmployeeIds = new HashSet<>();
    private String demandId;
    /**
     * The Review queue page (1-indexed, matching the URL the queue itself uses) this workspace was opened from, if any
     * — carried via the "page" query parameter on the RouterLink that opened it (see ReviewQueueView.buildRow()'s
     * comment). Threaded through every navigation back toward the queue below (the plain back link, and both of
     * decide()'s auto-navigate cases) so a reviewer working through page 6 of 13 doesn't get dumped back to page 1
     * after every single decision — the #1 UI/UX gap flagged on 2026-10-06.
     */
    private String fromPage;
    /**
     * The global "expand all" toggle from the requirements doc's "Card expand trigger" decision — "both mechanisms, not
     * one or the other" — for the audit case where a reviewer wants every raw value in front of them at once. ORed with
     * expandedEmployeeIds per-card, so toggling it off doesn't lose individually-expanded cards.
     */
    private boolean expandAll;
    /**
     * Override-eligible section collapse state (2026-10-07 decision) — collapsed by default. Same toggle-button
     * mechanism as {@link #buildExpandAllToggle()} (a local boolean + a Button that re-renders), not a Vaadin
     * Details/Accordion, to stay visually consistent with the rest of this screen rather than introducing a second
     * expand/collapse visual language. Independent of {@link #expandAll} — this toggles whether the override candidates
     * are shown at all, not whether each one's raw Expanded text is visible.
     */
    private boolean overrideExpanded;

    public ReviewWorkspaceView(DemandReviewService reviewService) {
        this.reviewService = reviewService;
        addClassName("bm-dash");
    }

    private static String initialsOf(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        StringBuilder initials = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty() && initials.length() < 2) {
                initials.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return initials.isEmpty() ? "?" : initials.toString();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    @Override
    public void setParameter(BeforeEvent event, String demandId) {
        this.demandId = demandId;
        List<String> pageParam = event.getLocation().getQueryParameters().getParameters().get("page");
        this.fromPage = (pageParam == null || pageParam.isEmpty()) ? null : pageParam.get(0);
        render();
    }

    private QueryParameters backQueryParameters() {
        return fromPage == null ? QueryParameters.empty() : QueryParameters.simple(Map.of("page", fromPage));
    }

    private void render() {
        removeAll();
        ReviewWorkspaceDto workspace = reviewService.workspace(demandId);

        add(buildBackLink());
        add(buildDemandHeader(workspace));
        add(buildExpandAllToggle());
        add(buildCandidateSection("Strong", workspace, "Strong", null));
        add(buildCandidateSection("Good", workspace, "Good", null));
        add(buildCandidateSection("Weak", workspace, "Weak", WEAK_DISPLAY_LIMIT));
        add(buildOverrideSection(workspace));
    }

    /**
     * "Both mechanisms, not one or the other" (requirements doc) — this sits alongside, not instead of, each card's own
     * click-to-expand.
     */
    private Div buildExpandAllToggle() {
        Button toggle = new Button(expandAll ? "Collapse all" : "Expand all", e -> {
            expandAll = !expandAll;
            render();
        });
        toggle.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);
        Div bar = new Div(toggle);
        bar.getStyle().set("display", "flex").set("justify-content", "flex-end");
        return bar;
    }

    private RouterLink buildBackLink() {
        RouterLink back = new RouterLink("← Back to queue", ReviewQueueView.class);
        back.addClassName("bm-queue-open");
        back.setQueryParameters(backQueryParameters());
        // Fix 2026-10-07: without this, the anchor — a direct child of this view's `.bm-dash` flex
        // column, whose default align-items is `stretch` — stretches to the full row width, making
        // the entire horizontal band clickable even though only the link text is visible.
        // align-self: flex-start shrinks the clickable area back down to the text's own width.
        back.getStyle().set("align-self", "flex-start");
        return back;
    }

    private Div buildDemandHeader(ReviewWorkspaceDto w) {
        Span title = new Span(w.demandId() + " — "
                + (w.subPersona() != null ? w.persona() + " - " + w.subPersona()
                : (w.persona() == null ? nz(w.clusterNameRaw()) : w.persona())));
        title.addClassName("bm-card-title-lg");

        String lifecycleLabel = switch (w.lifecycleState()) {
            case OPEN -> "Open";
            case FILLED -> "Filled";
            case EXHAUSTED -> "Exhausted — needs hiring";
        };
        Span subtitle = new Span(nz(w.location()) + "  ·  " + nz(w.band())
                + "  ·  Due: " + nz(w.dueCategory())
                + "  ·  Positions open: " + (w.balancePositions() == null ? 0 : w.balancePositions())
                + "  ·  " + nz(w.customer()) + (w.projectName() == null ? "" : " / " + w.projectName())
                + "  ·  Status: " + lifecycleLabel);
        subtitle.addClassName("bm-card-subtitle");

        Div titleBlock = new Div(title, subtitle);
        titleBlock.addClassName("bm-card-title-block");
        Div header = new Div(titleBlock);
        header.addClassName("bm-card");
        return header;
    }

    private Div buildCandidateSection(String heading, ReviewWorkspaceDto w, String tier, Integer limit) {
        List<ReviewCandidateDto> inTier = w.candidates().stream()
                .filter(c -> tier.equals(c.overallTier())).toList();
        if (limit != null && inTier.size() > limit) {
            inTier = inTier.subList(0, limit);
        }

        Span title = new Span(heading + " (" + inTier.size() + ")");
        title.addClassName("bm-card-title");

        Div rows = new Div();
        rows.addClassName("bm-cand-list");
        for (ReviewCandidateDto c : inTier) {
            rows.add(buildCandidateCard(c, false));
        }
        if (inTier.isEmpty()) {
            Span empty = new Span("No " + heading.toLowerCase(Locale.ROOT) + " candidates.");
            empty.addClassName("bm-card-subtitle");
            rows.add(empty);
        }

        Div card = new Div(title, rows);
        card.addClassName("bm-card");
        // 2026-10-07 "group fence" decision — see styles.css's .bm-cand-section-* comment. One
        // thin, tier-colored border per section (not per card, not just on the heading) so the
        // signal survives scrolling through the section, the way .bm-override-section already does.
        card.addClassName("bm-cand-section-" + tier.toLowerCase(Locale.ROOT));
        return card;
    }

    /**
     * The one-sub-band-below Override-eligible bucket — see ReviewCandidateDto.overrideEligible()'s Javadoc. A separate
     * section, never mixed into Strong/Good/Weak, so Override is never mistaken for an ordinary Propose (same reasoning
     * the requirements doc gives for keeping the action itself distinct). Omitted entirely when there's nothing in it,
     * rather than an always-present "(0)" section — unlike Strong/Good/Weak, which are always one of the demand's three
     * defined tiers, this bucket is the exception case, not a default empty state worth showing.
     */
    private Div buildOverrideSection(ReviewWorkspaceDto w) {
        List<ReviewCandidateDto> eligible = w.candidates().stream()
                .filter(ReviewCandidateDto::overrideEligible).toList();
        if (eligible.isEmpty()) {
            return new Div();
        }
        if (eligible.size() > OVERRIDE_DISPLAY_LIMIT) {
            eligible = eligible.subList(0, OVERRIDE_DISPLAY_LIMIT);
        }

        Span title = new Span("Override-eligible — one band below the ask (" + eligible.size() + ")");
        title.addClassName("bm-card-title");
        Span subtitle = new Span("Excluded by the engine's band rule, but close enough that a reviewer may "
                + "deliberately choose to propose anyway. Use Override, not Propose, so this is never read "
                + "back as an ordinary approval.");
        subtitle.addClassName("bm-card-subtitle");

        Button toggle = new Button(overrideExpanded ? "Hide candidates" : "Show candidates", e -> {
            overrideExpanded = !overrideExpanded;
            render();
        });
        toggle.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);

        Div headerRow = new Div(title, toggle);
        headerRow.getStyle().set("display", "flex").set("justify-content", "space-between").set("align-items", "center");

        Div card;
        if (overrideExpanded) {
            Div rows = new Div();
            rows.addClassName("bm-cand-list");
            for (ReviewCandidateDto c : eligible) {
                rows.add(buildCandidateCard(c, true));
            }
            card = new Div(headerRow, subtitle, rows);
        } else {
            card = new Div(headerRow, subtitle);
        }
        card.addClassName("bm-card");
        card.addClassName("bm-override-section");
        return card;
    }

    /**
     * One "two states, four dimensions" card. Collapsed by default: overall tier/one-liner plus four dimension rows
     * showing ONLY the plain-English Collapsed text. Expanded (per-card click, or the global "expand all" toggle): the
     * same four rows also show their raw-value Expanded text. All four dimensions expand together, never independently,
     * per the requirements doc.
     */
    private Div buildCandidateCard(ReviewCandidateDto c, boolean isOverride) {
        boolean expanded = expandAll || expandedEmployeeIds.contains(c.employeeId());

        Div avatar = new Div(new Span(initialsOf(c.employeeName())));
        avatar.addClassName("bm-queue-avatar");

        Span name = new Span(c.employeeName() + "  (#" + c.employeeId() + ")");
        name.addClassName("bm-queue-name");
        // Fix 2026-10-07: bm-queue-name/bm-queue-meta/bm-queue-name-line are the same classes
        // DashboardView.buildQueueRow already uses, and that one reads fine because its meta text
        // is deliberately prefixed with " · " — these are plain inline Spans with no CSS forcing
        // a line break, so without a separator they ran straight into the name with no gap at all
        // (e.g. "Abhijit Bhatta (#52279331)Persona match…"). Matches the existing convention
        // rather than inventing a new one.
        Span oneLiner = new Span(" · " + nz(c.oneLiner()));
        oneLiner.addClassName("bm-queue-meta");
        Div nameLine = new Div(name, oneLiner);
        nameLine.addClassName("bm-queue-name-line");
        nameLine.getStyle().set("cursor", "pointer");
        nameLine.addClickListener(e -> {
            if (expandedEmployeeIds.contains(c.employeeId())) {
                expandedEmployeeIds.remove(c.employeeId());
            } else {
                expandedEmployeeIds.add(c.employeeId());
            }
            render();
        });

        Span tierBadge = new Span(isOverride ? "ONE BAND BELOW" : c.overallTier().toUpperCase(Locale.ROOT));
        tierBadge.addClassName("bm-cand-tier-badge");
        tierBadge.addClassName(isOverride ? "bm-cand-tier-override" : "bm-cand-tier-" + c.overallTier().toLowerCase(Locale.ROOT));

        Div header = new Div(avatar, nameLine, tierBadge);
        header.addClassName("bm-cand-header");

        Div dims = new Div(
                buildDimensionRow("Skill", c.skillCollapsed(), c.skillExpanded(), expanded),
                buildDimensionRow("Band", c.bandCollapsed(), c.bandExpanded(), expanded),
                buildDimensionRow("Location", c.locationCollapsed(), c.locationExpanded(), expanded),
                buildDimensionRow("Assessment", c.assessmentCollapsed(), c.assessmentExpanded(), expanded)
        );
        dims.addClassName("bm-cand-dims");

        Div card = new Div(header, dims);
        card.addClassName("bm-cand-card");
        if (isOverride) {
            card.addClassName("bm-cand-card-override");
        }

        if (c.isDecided()) {
            card.addClassName("bm-candidate-row-decided");
            Span status = new Span(c.decisionStatus().toUpperCase(Locale.ROOT)
                    + (c.decidedByName() == null ? "" : " · " + c.decidedByName()));
            status.addClassName("bm-badge-danger");
            card.add(status);
        } else if (isOverride) {
            Button override = new Button("Override", e -> decide(c.employeeId(), DemandCandidateDecision.STATUS_OVERRIDDEN));
            override.addThemeVariants(ButtonVariant.SMALL);
            override.addClassName("bm-override-btn");
            Button reject = new Button("Reject", e -> decide(c.employeeId(), DemandCandidateDecision.STATUS_REJECTED));
            reject.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);
            Div actions = new Div(override, reject);
            actions.getStyle().set("display", "flex").set("gap", "8px").set("flex-shrink", "0");
            card.add(actions);
        } else {
            Button propose = new Button("Propose", e -> decide(c.employeeId(), DemandCandidateDecision.STATUS_APPROVED));
            propose.addThemeVariants(ButtonVariant.PRIMARY, ButtonVariant.SMALL);
            Button reject = new Button("Reject", e -> decide(c.employeeId(), DemandCandidateDecision.STATUS_REJECTED));
            reject.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);
            Div actions = new Div(propose, reject);
            actions.getStyle().set("display", "flex").set("gap", "8px").set("flex-shrink", "0");
            card.add(actions);
        }

        return card;
    }

    /**
     * One dimension row. Collapsed text always shows; Expanded text (the raw values) is appended only when
     * {@code expanded} is true — never shown alone, per the card spec.
     */
    private Div buildDimensionRow(String label, String collapsed, String expandedText, boolean expanded) {
        Span labelSpan = new Span(label);
        labelSpan.addClassName("bm-cand-dim-label");
        Span collapsedSpan = new Span(nz(collapsed));
        collapsedSpan.addClassName("bm-cand-dim-collapsed");

        Div row = new Div(labelSpan, collapsedSpan);
        row.addClassName("bm-cand-dim-row");
        if (expanded && expandedText != null && !expandedText.isBlank()) {
            Span expandedSpan = new Span(expandedText);
            expandedSpan.addClassName("bm-cand-dim-expanded");
            row.add(expandedSpan);
        }
        return row;
    }

    private void decide(Long employeeId, String action) {
        ReviewWorkspaceDto updated = reviewService.decide(demandId, employeeId, new DecisionRequest(action));
        if (updated.lifecycleState() != DemandLifecycleState.OPEN) {
            // That was the last undecided Strong/Good candidate — auto-advance per the locked-in
            // UX decision, rather than leaving the reviewer looking at a demand with nothing left
            // to decide.
            String next = updated.nextDemandId();
            if (next != null) {
                // "review/demand/<id>" is this view's own @Route path (value = "review/demand") —
                // the string-plus-QueryParameters overload is used instead of the Class-based one
                // specifically so fromPage survives the auto-advance (see this field's Javadoc);
                // there's no Class-based navigate(...) overload that also takes QueryParameters.
                UI.getCurrent().navigate("review/demand/" + next, backQueryParameters());
                return;
            }
            UI.getCurrent().navigate("review", backQueryParameters());
            return;
        }
        render();
    }
}
