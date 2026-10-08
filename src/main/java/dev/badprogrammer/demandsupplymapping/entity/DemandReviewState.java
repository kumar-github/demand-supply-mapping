package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * PERSISTENT — per-demand reviewer state, created by V1__init_schema.sql but unused by any Java code until now (same
 * situation {@link DemandCandidateDecision} was in before the Review feature was built — see full_db_design.sql section
 * 3). A refresh/matching run must never write to this table; {@code demand_id} is a plain
 * {@code TEXT PRIMARY KEY REFERENCES demand_enriched}, not a generated id, so the app assigns it explicitly rather than
 * via {@code @GeneratedValue}.
 * <p>
 * First real use (2026-10-06): the Review queue's "Flag for hiring" action on EXHAUSTED demands (every Strong/Good
 * candidate already Proposed/Rejected, but positions remain open). This originally repurposed
 * {@code needsReattention}/{@code reattentionReason} for it, since that EXHAUSTED signal looked like the same "needs a
 * human to look again" shape — but {@code needsReattention} already had an owner: V1's own schema comment scopes it to
 * a future RefreshService detecting the source Excel row changed under an open review (see RefreshService's "Known
 * gaps" comment — still unimplemented). Those are two different events with different triggers (automated/refresh vs.
 * manual/reviewer) and no reason to share state, so V12__flagged_for_hiring_column.sql split them:
 * {@code flaggedForHiring}/ {@code flaggedForHiringReason}/{@code flaggedForHiringBy}/{@code flaggedForHiringAt} is the
 * "Flag for hiring" action's own field now, and {@code needsReattention}/{@code reattentionReason} are left exactly as
 * V1 defined them, free for RefreshService's still-unbuilt purpose.
 */
@Entity
@Table(name = "demand_review_state")
public class DemandReviewState {

    @Id
    @Column(name = "demand_id")
    private String demandId;

    @Column(name = "status", nullable = false)
    private String status = "open";

    @Column(name = "assigned_reviewer")
    private Integer assignedReviewer;

    @Column(name = "notes")
    private String notes;

    /**
     * Reserved for RefreshService's still-unbuilt "source changed under an open review" signal (V1__init_schema.sql's
     * own intent) — NOT written to by {@link dev.badprogrammer.demandsupplymapping.review.DemandReviewService} any more
     * as of V12; see this class's Javadoc.
     */
    @Column(name = "needs_reattention", nullable = false)
    private boolean needsReattention;

    @Column(name = "reattention_reason")
    private String reattentionReason;

    @Column(name = "flagged_for_hiring", nullable = false)
    private boolean flaggedForHiring;

    @Column(name = "flagged_for_hiring_reason")
    private String flaggedForHiringReason;

    @Column(name = "flagged_for_hiring_by")
    private Integer flaggedForHiringBy;

    @Column(name = "flagged_for_hiring_at")
    private OffsetDateTime flaggedForHiringAt;

    @Column(name = "closed_by")
    private Integer closedBy;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    protected DemandReviewState() {
    }

    public DemandReviewState(String demandId) {
        this.demandId = demandId;
    }

    public String getDemandId() {
        return demandId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAssignedReviewer() {
        return assignedReviewer;
    }

    public void setAssignedReviewer(Integer assignedReviewer) {
        this.assignedReviewer = assignedReviewer;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isNeedsReattention() {
        return needsReattention;
    }

    public void setNeedsReattention(boolean needsReattention) {
        this.needsReattention = needsReattention;
    }

    public String getReattentionReason() {
        return reattentionReason;
    }

    public void setReattentionReason(String reattentionReason) {
        this.reattentionReason = reattentionReason;
    }

    public boolean isFlaggedForHiring() {
        return flaggedForHiring;
    }

    public void setFlaggedForHiring(boolean flaggedForHiring) {
        this.flaggedForHiring = flaggedForHiring;
    }

    public String getFlaggedForHiringReason() {
        return flaggedForHiringReason;
    }

    public void setFlaggedForHiringReason(String flaggedForHiringReason) {
        this.flaggedForHiringReason = flaggedForHiringReason;
    }

    public Integer getFlaggedForHiringBy() {
        return flaggedForHiringBy;
    }

    public void setFlaggedForHiringBy(Integer flaggedForHiringBy) {
        this.flaggedForHiringBy = flaggedForHiringBy;
    }

    public OffsetDateTime getFlaggedForHiringAt() {
        return flaggedForHiringAt;
    }

    public void setFlaggedForHiringAt(OffsetDateTime flaggedForHiringAt) {
        this.flaggedForHiringAt = flaggedForHiringAt;
    }

    public Integer getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(Integer closedBy) {
        this.closedBy = closedBy;
    }

    public OffsetDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(OffsetDateTime closedAt) {
        this.closedAt = closedAt;
    }
}
