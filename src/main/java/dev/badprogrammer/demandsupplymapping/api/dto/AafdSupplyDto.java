package dev.badprogrammer.demandsupplymapping.api.dto;

import dev.badprogrammer.demandsupplymapping.entity.AafdSupplyEnriched;

import java.time.OffsetDateTime;

/**
 * Read-only projection of AafdSupplyEnriched for the AAFD Supply grid (SupplyDemandView's "AAFD Supply" tab) and any
 * future REST exposure. Simpler than SupplyDto — no persona/subPersona (AAFD isn't classified yet; see
 * AafdSupplyEnriched's own Javadoc), so this is a flat field-for-field mirror of the entity with no derived values.
 */
public record AafdSupplyDto(
        Long empCode,
        String name,
        String offshoreOnshore,
        String availabilityDate,
        String month,
        String status,
        String band,
        String subBand,
        String primarySkill,
        String capability,
        String subCapability,
        String location,
        String customer,
        String reasonOfRelease,
        String deploymentStatus,
        String resourceRoleType,
        String durationInProjectMonths,
        String skillCluster,
        String skillClusterPersona,
        String rating,
        boolean isActive,
        OffsetDateTime lastRefreshedAt
) {
    public static AafdSupplyDto from(AafdSupplyEnriched e) {
        return new AafdSupplyDto(
                e.getEmpCode(),
                e.getName(),
                e.getOffshoreOnshore(),
                e.getAvailabilityDate(),
                e.getMonth(),
                e.getStatus(),
                e.getBand(),
                e.getSubBand(),
                e.getPrimarySkill(),
                e.getCapability(),
                e.getSubCapability(),
                e.getLocation(),
                e.getCustomer(),
                e.getReasonOfRelease(),
                e.getDeploymentStatus(),
                e.getResourceRoleType(),
                e.getDurationInProjectMonths(),
                e.getSkillCluster(),
                e.getSkillClusterPersona(),
                e.getRating(),
                e.isActive(),
                e.getLastRefreshedAt()
        );
    }
}
