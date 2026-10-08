package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * RE-DERIVABLE — one row per demand/requisition, rebuilt on every Demand.xlsx refresh. See full_db_design.sql for
 * column rationale.
 */
@Entity
@Table(name = "demand_enriched")
public class DemandEnriched {

    @Id
    @Column(name = "demand_id")
    private String demandId;

    @Column(name = "cluster_name_raw")
    private String clusterNameRaw;

    @Column(name = "final_skill_cluster_raw")
    private String finalSkillClusterRaw;

    @Column(name = "mas_mapping_raw")
    private String masMappingRaw;

    @Column(name = "additional_request_raw")
    private String additionalRequestRaw;

    @Column(name = "location")
    private String location;

    @Column(name = "band")
    private String band;

    // Added 2026-09-29 (V3 migration) — for cap_employee_rows()'s
    // per-employee ranking (ageing urgency first, balance_positions as
    // tie-break).
    @Column(name = "balance_positions")
    private Integer balancePositions;

    @Column(name = "due_category")
    private String dueCategory;

    // Added 2026-09-29 (V4 migration) — ClassificationResult.status() was
    // already computed by DemandClassifier but never persisted (found
    // while building MatchingRunService). Correctness-affecting, not just
    // display: skillSignal()'s weak-MERN detection needs this exact text
    // (see V4 migration's comment).
    @Column(name = "classification_note")
    private String classificationNote;

    // Added 2026-09-30 (V6 migration) — raw 'Customer'/'Project Name' columns,
    // needed for task #19's shortlist-workbook export (build_matches.py reads
    // both straight through as display-only fields on each match row). Never
    // read by classification or matching.
    @Column(name = "customer")
    private String customer;

    @Column(name = "project_name")
    private String projectName;

    // Raw 'New-Ageing' column — the display "Ageing Bucket" text shown on each
    // shortlist row, distinct from due_category above (which is what
    // MatchingService.ageingRank() sorts by; build_matches.py reads the two
    // from different raw columns — Due Category_New vs New-Ageing).
    @Column(name = "new_ageing")
    private String newAgeing;

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

    @Column(name = "mas_mapping_mismatch_flag", nullable = false)
    private boolean masMappingMismatchFlag = false;

    @Column(name = "frontend_anchor_flag", nullable = false)
    private boolean frontendAnchorFlag = false;

    @Column(name = "source_row_hash")
    private String sourceRowHash;

    @Column(name = "last_refreshed_at", nullable = false)
    private OffsetDateTime lastRefreshedAt = OffsetDateTime.now();

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "inactive_since")
    private OffsetDateTime inactiveSince;

    protected DemandEnriched() {
    }

    public DemandEnriched(String demandId) {
        this.demandId = demandId;
    }

    public String getDemandId() {
        return demandId;
    }

    public void setDemandId(String demandId) {
        this.demandId = demandId;
    }

    public String getFinalSkillClusterRaw() {
        return finalSkillClusterRaw;
    }

    public void setFinalSkillClusterRaw(String finalSkillClusterRaw) {
        this.finalSkillClusterRaw = finalSkillClusterRaw;
    }

    public String getMasMappingRaw() {
        return masMappingRaw;
    }

    public void setMasMappingRaw(String masMappingRaw) {
        this.masMappingRaw = masMappingRaw;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getBand() {
        return band;
    }

    public void setBand(String band) {
        this.band = band;
    }

    public Integer getBalancePositions() {
        return balancePositions;
    }

    public void setBalancePositions(Integer balancePositions) {
        this.balancePositions = balancePositions;
    }

    public String getDueCategory() {
        return dueCategory;
    }

    public void setDueCategory(String dueCategory) {
        this.dueCategory = dueCategory;
    }

    public String getClassificationNote() {
        return classificationNote;
    }

    public void setClassificationNote(String classificationNote) {
        this.classificationNote = classificationNote;
    }

    public String getCustomer() {
        return customer;
    }

    public void setCustomer(String customer) {
        this.customer = customer;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getNewAgeing() {
        return newAgeing;
    }

    public void setNewAgeing(String newAgeing) {
        this.newAgeing = newAgeing;
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

    // Added 2026-09-28 for RefreshService — these fields existed on the
    // entity already but had no accessors yet.
    public String getClusterNameRaw() {
        return clusterNameRaw;
    }

    public void setClusterNameRaw(String clusterNameRaw) {
        this.clusterNameRaw = clusterNameRaw;
    }

    public String getAdditionalRequestRaw() {
        return additionalRequestRaw;
    }

    public void setAdditionalRequestRaw(String additionalRequestRaw) {
        this.additionalRequestRaw = additionalRequestRaw;
    }

    public boolean isMasMappingMismatchFlag() {
        return masMappingMismatchFlag;
    }

    public void setMasMappingMismatchFlag(boolean masMappingMismatchFlag) {
        this.masMappingMismatchFlag = masMappingMismatchFlag;
    }

    public boolean isFrontendAnchorFlag() {
        return frontendAnchorFlag;
    }

    public void setFrontendAnchorFlag(boolean frontendAnchorFlag) {
        this.frontendAnchorFlag = frontendAnchorFlag;
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

    public OffsetDateTime getInactiveSince() {
        return inactiveSince;
    }

    public void setInactiveSince(OffsetDateTime inactiveSince) {
        this.inactiveSince = inactiveSince;
    }
}
