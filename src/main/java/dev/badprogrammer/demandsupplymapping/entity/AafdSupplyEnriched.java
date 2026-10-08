package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * RE-DERIVABLE — one row per AAFD-Supply.xlsx employee, rebuilt on every AAFD refresh. See
 * V13__aafd_supply_enriched.sql for the full rationale (separate table from supply_enriched, no classification/persona
 * link yet). Field names below mirror the real export's own header names (confirmed by reading 02-AAFD Supply.xlsx
 * directly), not supply_enriched's naming — e.g. {@code subCapability} here is a plain field, not the hyphenated
 * {@code sub-capability} concept supply_enriched's classification logic uses, because nothing here is derived from or
 * compared against that logic.
 */
@Entity
@Table(name = "aafd_supply_enriched")
public class AafdSupplyEnriched {

    @Id
    @Column(name = "emp_code")
    private Long empCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "offshore_onshore")
    private String offshoreOnshore;

    @Column(name = "wpc_notice_shared_on")
    private String wpcNoticeSharedOn;

    @Column(name = "availability_date")
    private String availabilityDate;

    @Column(name = "month")
    private String month;

    @Column(name = "status")
    private String status;

    @Column(name = "band")
    private String band;

    @Column(name = "sub_band")
    private String subBand;

    @Column(name = "skill_confirmed_by_emp")
    private String skillConfirmedByEmp;

    @Column(name = "primary_skill")
    private String primarySkill;

    @Column(name = "capability")
    private String capability;

    @Column(name = "sub_capability")
    private String subCapability;

    @Column(name = "location")
    private String location;

    @Column(name = "prime_nv")
    private String primeNv;

    @Column(name = "customer")
    private String customer;

    @Column(name = "reason_of_release")
    private String reasonOfRelease;

    @Column(name = "deployment_status")
    private String deploymentStatus;

    @Column(name = "sap_non_sap")
    private String sapNonSap;

    @Column(name = "resource_role_type")
    private String resourceRoleType;

    @Column(name = "duration_in_project_months")
    private String durationInProjectMonths;

    @Column(name = "detailed_remarks")
    private String detailedRemarks;

    @Column(name = "l3")
    private String l3;

    @Column(name = "l4")
    private String l4;

    @Column(name = "global_vertical")
    private String globalVertical;

    @Column(name = "mas_mapping")
    private String masMapping;

    @Column(name = "hr_l4")
    private String hrL4;

    // The source file's own, separate 'MAS' column — see V13's migration comment for why this
    // isn't folded into masMapping.
    @Column(name = "mas_raw")
    private String masRaw;

    @Column(name = "skill_cluster")
    private String skillCluster;

    @Column(name = "skill_cluster_persona")
    private String skillClusterPersona;

    @Column(name = "rating")
    private String rating;

    @Column(name = "last_refreshed_at", nullable = false)
    private OffsetDateTime lastRefreshedAt = OffsetDateTime.now();

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "inactive_since")
    private OffsetDateTime inactiveSince;

    protected AafdSupplyEnriched() {
    }

    public AafdSupplyEnriched(Long empCode) {
        this.empCode = empCode;
    }

    public Long getEmpCode() {
        return empCode;
    }

    public void setEmpCode(Long empCode) {
        this.empCode = empCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOffshoreOnshore() {
        return offshoreOnshore;
    }

    public void setOffshoreOnshore(String offshoreOnshore) {
        this.offshoreOnshore = offshoreOnshore;
    }

    public String getWpcNoticeSharedOn() {
        return wpcNoticeSharedOn;
    }

    public void setWpcNoticeSharedOn(String wpcNoticeSharedOn) {
        this.wpcNoticeSharedOn = wpcNoticeSharedOn;
    }

    public String getAvailabilityDate() {
        return availabilityDate;
    }

    public void setAvailabilityDate(String availabilityDate) {
        this.availabilityDate = availabilityDate;
    }

    public String getMonth() {
        return month;
    }

    public void setMonth(String month) {
        this.month = month;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getSkillConfirmedByEmp() {
        return skillConfirmedByEmp;
    }

    public void setSkillConfirmedByEmp(String skillConfirmedByEmp) {
        this.skillConfirmedByEmp = skillConfirmedByEmp;
    }

    public String getPrimarySkill() {
        return primarySkill;
    }

    public void setPrimarySkill(String primarySkill) {
        this.primarySkill = primarySkill;
    }

    public String getCapability() {
        return capability;
    }

    public void setCapability(String capability) {
        this.capability = capability;
    }

    public String getSubCapability() {
        return subCapability;
    }

    public void setSubCapability(String subCapability) {
        this.subCapability = subCapability;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getPrimeNv() {
        return primeNv;
    }

    public void setPrimeNv(String primeNv) {
        this.primeNv = primeNv;
    }

    public String getCustomer() {
        return customer;
    }

    public void setCustomer(String customer) {
        this.customer = customer;
    }

    public String getReasonOfRelease() {
        return reasonOfRelease;
    }

    public void setReasonOfRelease(String reasonOfRelease) {
        this.reasonOfRelease = reasonOfRelease;
    }

    public String getDeploymentStatus() {
        return deploymentStatus;
    }

    public void setDeploymentStatus(String deploymentStatus) {
        this.deploymentStatus = deploymentStatus;
    }

    public String getSapNonSap() {
        return sapNonSap;
    }

    public void setSapNonSap(String sapNonSap) {
        this.sapNonSap = sapNonSap;
    }

    public String getResourceRoleType() {
        return resourceRoleType;
    }

    public void setResourceRoleType(String resourceRoleType) {
        this.resourceRoleType = resourceRoleType;
    }

    public String getDurationInProjectMonths() {
        return durationInProjectMonths;
    }

    public void setDurationInProjectMonths(String durationInProjectMonths) {
        this.durationInProjectMonths = durationInProjectMonths;
    }

    public String getDetailedRemarks() {
        return detailedRemarks;
    }

    public void setDetailedRemarks(String detailedRemarks) {
        this.detailedRemarks = detailedRemarks;
    }

    public String getL3() {
        return l3;
    }

    public void setL3(String l3) {
        this.l3 = l3;
    }

    public String getL4() {
        return l4;
    }

    public void setL4(String l4) {
        this.l4 = l4;
    }

    public String getGlobalVertical() {
        return globalVertical;
    }

    public void setGlobalVertical(String globalVertical) {
        this.globalVertical = globalVertical;
    }

    public String getMasMapping() {
        return masMapping;
    }

    public void setMasMapping(String masMapping) {
        this.masMapping = masMapping;
    }

    public String getHrL4() {
        return hrL4;
    }

    public void setHrL4(String hrL4) {
        this.hrL4 = hrL4;
    }

    public String getMasRaw() {
        return masRaw;
    }

    public void setMasRaw(String masRaw) {
        this.masRaw = masRaw;
    }

    public String getSkillCluster() {
        return skillCluster;
    }

    public void setSkillCluster(String skillCluster) {
        this.skillCluster = skillCluster;
    }

    public String getSkillClusterPersona() {
        return skillClusterPersona;
    }

    public void setSkillClusterPersona(String skillClusterPersona) {
        this.skillClusterPersona = skillClusterPersona;
    }

    public String getRating() {
        return rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public OffsetDateTime getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    public void setLastRefreshedAt(OffsetDateTime lastRefreshedAt) {
        this.lastRefreshedAt = lastRefreshedAt;
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
}
