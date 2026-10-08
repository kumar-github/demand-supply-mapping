package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * PERSISTENT — full audit trail of status changes on a {@link DemandCandidateDecision}, so "approved then rejected then
 * approved again" isn't lost to last-write-wins. Written alongside every status change DemandReviewService makes, never
 * by a refresh. See full_db_design.sql section 3.
 */
@Entity
@Table(name = "decision_history")
public class DecisionHistory {

    // Both SERIAL/INTEGER in the schema, not BIGSERIAL/BIGINT — Integer here, not Long, to match
    // (same fix as DemandCandidateDecision.decisionId; this table's decision_id FKs that one).
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Integer historyId;

    @Column(name = "decision_id", nullable = false)
    private Integer decisionId;

    @Column(name = "old_status")
    private String oldStatus;

    @Column(name = "new_status", nullable = false)
    private String newStatus;

    // NOT NULL in the schema deliberately (this table exists specifically to answer "who did
    // this") — see V10__reviewer_default_user.sql for how this is populated before real auth
    // exists.
    @Column(name = "changed_by", nullable = false)
    private Integer changedBy;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt = OffsetDateTime.now();

    protected DecisionHistory() {
    }

    public DecisionHistory(Integer decisionId, String oldStatus, String newStatus, Integer changedBy) {
        this.decisionId = decisionId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.changedBy = changedBy;
    }

    public Integer getHistoryId() {
        return historyId;
    }

    public Integer getDecisionId() {
        return decisionId;
    }

    public String getOldStatus() {
        return oldStatus;
    }

    public String getNewStatus() {
        return newStatus;
    }

    public Integer getChangedBy() {
        return changedBy;
    }

    public OffsetDateTime getChangedAt() {
        return changedAt;
    }
}
