package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * PERSISTENT — one row per (demand, employee) pair a reviewer has actually acted on. This is NOT the engine's proposed
 * candidate list (that's {@link MatchCandidate}, re-derivable) — it's the subset a human has looked at and made a call
 * on, so a refresh/matching run must never write to this table. See full_db_design.sql section 3 for the full
 * rationale.
 * <p>
 * Pair-level, not side-level: this is the ONE shared decision for an (employeeId, demandId) pair, deliberately not
 * duplicated per entry point — a supply-side review screen and this demand-side one both read/write the same row via
 * {@code UNIQUE (demand_id, employee_id)}, upserting rather than blind-inserting. Only the demand-side entry point
 * (DemandReviewService) is built so far.
 */
@Entity
@Table(name = "demand_candidate_decisions")
public class DemandCandidateDecision {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_STAFFED = "staffed";
    /**
     * The "Override" action (requirements doc's Review interaction model) — deliberately distinct from
     * {@link #STATUS_APPROVED} so it's never mistaken for an ordinary Propose. Only valid for a candidate whose engine
     * tier at decision time was "Excluded" for being exactly one sub-band below the demand's band (see
     * MatchingService.bandSignal()'s "below_one" quality and DemandReviewService's OVERRIDE_ELIGIBLE_BAND_QUALITY) —
     * added by V11__override_decision_status.sql.
     */
    public static final String STATUS_OVERRIDDEN = "overridden";

    // SERIAL in the schema (32-bit), not BIGSERIAL — Integer here, not Long, to match. decision_history.decision_id
    // (which FKs this) is declared INTEGER for the same reason.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "decision_id")
    private Integer decisionId;

    @Column(name = "demand_id", nullable = false)
    private String demandId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    /**
     * Strong/Good/Weak snapshot at decision time — so a later matching run reclassifying this pair doesn't silently
     * rewrite the history of what the reviewer actually saw when they decided.
     */
    @Column(name = "engine_tier_at_decision")
    private String engineTierAtDecision;

    @Column(name = "status", nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "notes")
    private String notes;

    @Column(name = "decided_by")
    private Integer decidedBy;

    @Column(name = "decided_at", nullable = false)
    private OffsetDateTime decidedAt = OffsetDateTime.now();

    protected DemandCandidateDecision() {
    }

    public DemandCandidateDecision(String demandId, Long employeeId) {
        this.demandId = demandId;
        this.employeeId = employeeId;
    }

    public Integer getDecisionId() {
        return decisionId;
    }

    public String getDemandId() {
        return demandId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getEngineTierAtDecision() {
        return engineTierAtDecision;
    }

    public void setEngineTierAtDecision(String engineTierAtDecision) {
        this.engineTierAtDecision = engineTierAtDecision;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Integer getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(Integer decidedBy) {
        this.decidedBy = decidedBy;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(OffsetDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }
}
