package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * RE-DERIVABLE — one row per bench employee, rebuilt on every AFD-Supply.xlsx refresh. See full_db_design.sql for the
 * authoritative column-by-column rationale (is_active/inactive_since soft-delete, sub_capability check-only flag, etc.)
 * — kept in sync with it here.
 */
@Entity
@Table(name = "supply_enriched")
public class SupplyEnriched {

    @Id
    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "employee_name", nullable = false)
    private String employeeName;

    @Column(name = "band")
    private String band;

    // Added 2026-09-29 (V2 migration) — fine-grained band ('E1.1'..'E3.2'),
    // distinct from the coarse `band` above. Drives the band-ladder DATA
    // ISSUE override and is what MatchingService.bandSignal() needs.
    @Column(name = "sub_band")
    private String subBand;

    @Column(name = "location")
    private String location;

    @Column(name = "capability")
    private String capability;

    @Column(name = "afd_status")
    private String afdStatus;

    @Column(name = "skill_cluster_raw")
    private String skillClusterRaw;

    // Added 2026-09-29 (V2 migration) — raw 'Bench Ageing days' column
    // (numeric day count), picked over the source file's bucketed
    // 'Duration' column since a bucket is derivable from days and not
    // the reverse.
    @Column(name = "bench_ageing_days")
    private Integer benchAgeingDays;

    // Added 2026-09-29 (V3 migration) — for assessment_signal()'s one-liner
    // clause and cap_employee_rows()'s per-employee ranking.
    @Column(name = "rating")
    private String rating;

    @Column(name = "score")
    private Double score;

    // Added 2026-09-29 (V4 migration) — ClassificationResult.completeness()
    // was already computed during classification but never persisted
    // (found while building MatchingRunService). Display-only: feeds
    // skillSignal()'s one-liner text, never a tier/quality decision.
    @Column(name = "completeness")
    private String completeness;

    // Added 2026-09-30 (V6 migration) — raw 'Prime/NV' column, needed for
    // task #19's shortlist-workbook export (write_shortlist_workbook.py's
    // employee header line shows it). Display-only pass-through, like
    // completeness above: never read by classification or matching.
    @Column(name = "prime_nv")
    private String primeNv;

    // Added 2026-09-30 (V6 migration) — ClassificationResult.status(), the
    // human-readable "why is this unclassified/DATA ISSUE" reason, was
    // already computed by classification but silently dropped, same bug
    // demand_enriched.classification_note (V4) already fixed on that side.
    // Needed for the shortlist workbook's DATA-ISSUE trailer block.
    @Column(name = "classification_note")
    private String classificationNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "persona_id")
    private Persona persona;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_persona_id")
    private SubPersona subPersona;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "named_accessories", columnDefinition = "text[]")
    private List<String> namedAccessories;

    @Column(name = "framework_confirmed")
    private Boolean frameworkConfirmed;

    @Column(name = "classification_status", nullable = false)
    private String classificationStatus = "classified";

    // Added 2026-10-01 (V8 migration) — mirrors demand_enriched's mas_mapping_raw (V1). Needed for
    // MatchingService.masMappingCrossCheck(), which compares this employee's own raw MAS Mapping
    // against the matched demand's raw MAS Mapping — never used to filter/match beyond the
    // existing Phase 1 scope gate.
    @Column(name = "mas_mapping_raw")
    private String masMappingRaw;

    @Column(name = "sub_capability_raw")
    private String subCapabilityRaw;

    @Column(name = "sub_capability_mismatch_flag", nullable = false)
    private boolean subCapabilityMismatchFlag = false;

    @Column(name = "source_row_hash")
    private String sourceRowHash;

    @Column(name = "last_refreshed_at", nullable = false)
    private OffsetDateTime lastRefreshedAt = OffsetDateTime.now();

    // Task 2026-09-27 review fix — mirrors demand_enriched's existing
    // soft-delete pattern; see full_db_design.sql revision note.
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "inactive_since")
    private OffsetDateTime inactiveSince;

    protected SupplyEnriched() {
    }

    public SupplyEnriched(Long employeeId) {
        this.employeeId = employeeId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Long employeeId) {
        this.employeeId = employeeId;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName;
    }

    public String getBand() {
        return band;
    }

    public void setBand(String band) {
        this.band = band;
    }

    public String getSubBand() {
        return subBand;
    }

    public void setSubBand(String subBand) {
        this.subBand = subBand;
    }

    public Integer getBenchAgeingDays() {
        return benchAgeingDays;
    }

    public void setBenchAgeingDays(Integer benchAgeingDays) {
        this.benchAgeingDays = benchAgeingDays;
    }

    public String getRating() {
        return rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getCompleteness() {
        return completeness;
    }

    public void setCompleteness(String completeness) {
        this.completeness = completeness;
    }

    public String getPrimeNv() {
        return primeNv;
    }

    public void setPrimeNv(String primeNv) {
        this.primeNv = primeNv;
    }

    public String getClassificationNote() {
        return classificationNote;
    }

    public void setClassificationNote(String classificationNote) {
        this.classificationNote = classificationNote;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCapability() {
        return capability;
    }

    public void setCapability(String capability) {
        this.capability = capability;
    }

    public String getAfdStatus() {
        return afdStatus;
    }

    public void setAfdStatus(String afdStatus) {
        this.afdStatus = afdStatus;
    }

    public String getSkillClusterRaw() {
        return skillClusterRaw;
    }

    public void setSkillClusterRaw(String skillClusterRaw) {
        this.skillClusterRaw = skillClusterRaw;
    }

    public Persona getPersona() {
        return persona;
    }

    public void setPersona(Persona persona) {
        this.persona = persona;
    }

    public SubPersona getSubPersona() {
        return subPersona;
    }

    public void setSubPersona(SubPersona subPersona) {
        this.subPersona = subPersona;
    }

    public List<String> getNamedAccessories() {
        return namedAccessories;
    }

    public void setNamedAccessories(List<String> namedAccessories) {
        this.namedAccessories = namedAccessories;
    }

    public Boolean getFrameworkConfirmed() {
        return frameworkConfirmed;
    }

    public void setFrameworkConfirmed(Boolean frameworkConfirmed) {
        this.frameworkConfirmed = frameworkConfirmed;
    }

    public String getClassificationStatus() {
        return classificationStatus;
    }

    public void setClassificationStatus(String classificationStatus) {
        this.classificationStatus = classificationStatus;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public OffsetDateTime getInactiveSince() {
        return inactiveSince;
    }

    public void setInactiveSince(OffsetDateTime inactiveSince) {
        this.inactiveSince = inactiveSince;
    }

    public String getMasMappingRaw() {
        return masMappingRaw;
    }

    public void setMasMappingRaw(String masMappingRaw) {
        this.masMappingRaw = masMappingRaw;
    }

    // Added 2026-09-28 for RefreshService — these fields existed on the
    // entity already but had no accessors yet.
    public String getSubCapabilityRaw() {
        return subCapabilityRaw;
    }

    public void setSubCapabilityRaw(String subCapabilityRaw) {
        this.subCapabilityRaw = subCapabilityRaw;
    }

    public boolean isSubCapabilityMismatchFlag() {
        return subCapabilityMismatchFlag;
    }

    public void setSubCapabilityMismatchFlag(boolean subCapabilityMismatchFlag) {
        this.subCapabilityMismatchFlag = subCapabilityMismatchFlag;
    }

    public String getSourceRowHash() {
        return sourceRowHash;
    }

    public void setSourceRowHash(String sourceRowHash) {
        this.sourceRowHash = sourceRowHash;
    }

    public OffsetDateTime getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    public void setLastRefreshedAt(OffsetDateTime lastRefreshedAt) {
        this.lastRefreshedAt = lastRefreshedAt;
    }
}
