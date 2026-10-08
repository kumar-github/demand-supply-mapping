package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * RE-DERIVABLE — the actual output of MatchingService, one row per (employee, demand, run). Fully rewritten every time
 * matching runs.
 */
@Entity
@Table(name = "match_candidates")
public class MatchCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "match_id")
    private Long matchId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "demand_id", nullable = false)
    private String demandId;

    @Column(name = "overall_tier", nullable = false)
    private String overallTier; // Strong | Good | Weak | Excluded

    @Column(name = "skill_signal")
    private String skillSignal;

    @Column(name = "band_signal")
    private String bandSignal;

    @Column(name = "location_signal")
    private String locationSignal;

    @Column(name = "one_liner")
    private String oneLiner;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt = OffsetDateTime.now();

    protected MatchCandidate() {
    }

    public MatchCandidate(Long employeeId, String demandId, String overallTier,
                          String skillSignal, String bandSignal, String locationSignal,
                          String oneLiner, Long runId) {
        this.employeeId = employeeId;
        this.demandId = demandId;
        this.overallTier = overallTier;
        this.skillSignal = skillSignal;
        this.bandSignal = bandSignal;
        this.locationSignal = locationSignal;
        this.oneLiner = oneLiner;
        this.runId = runId;
    }

    public Long getMatchId() {
        return matchId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getDemandId() {
        return demandId;
    }

    public String getOverallTier() {
        return overallTier;
    }

    public String getSkillSignal() {
        return skillSignal;
    }

    public String getBandSignal() {
        return bandSignal;
    }

    public String getLocationSignal() {
        return locationSignal;
    }

    public String getOneLiner() {
        return oneLiner;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }
}
