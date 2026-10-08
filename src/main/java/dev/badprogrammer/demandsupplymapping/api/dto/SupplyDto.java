package dev.badprogrammer.demandsupplymapping.api.dto;

import dev.badprogrammer.demandsupplymapping.entity.SupplyEnriched;
import dev.badprogrammer.demandsupplymapping.refresh.RefreshLogic;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only projection of SupplyEnriched for the REST API — never returns the entity directly (its Persona/SubPersona
 * associations are lazy and would either serialize as proxies or blow up outside a transaction).
 * <p>
 * subCapabilityNote is computed on the fly from stored subCapabilityRaw + persona (see RefreshLogic.subCapabilityNote's
 * own doc for why it isn't a stored column).
 */
public record SupplyDto(
        Long employeeId,
        String employeeName,
        String band,
        String subBand,
        String location,
        String capability,
        String afdStatus,
        String skillClusterRaw,
        String persona,
        String subPersona,
        List<String> namedAccessories,
        Boolean frameworkConfirmed,
        String classificationStatus,
        String subCapabilityRaw,
        boolean subCapabilityMismatchFlag,
        String subCapabilityNote,
        Integer benchAgeingDays,
        boolean isActive,
        OffsetDateTime lastRefreshedAt
) {
    public static SupplyDto from(SupplyEnriched e) {
        String personaName = e.getPersona() == null ? null : e.getPersona().getName();
        return new SupplyDto(
                e.getEmployeeId(),
                e.getEmployeeName(),
                e.getBand(),
                e.getSubBand(),
                e.getLocation(),
                e.getCapability(),
                e.getAfdStatus(),
                e.getSkillClusterRaw(),
                personaName,
                e.getSubPersona() == null ? null : e.getSubPersona().getName(),
                e.getNamedAccessories(),
                e.getFrameworkConfirmed(),
                e.getClassificationStatus(),
                e.getSubCapabilityRaw(),
                e.isSubCapabilityMismatchFlag(),
                RefreshLogic.subCapabilityNote(e.getSubCapabilityRaw(), personaName),
                e.getBenchAgeingDays(),
                e.isActive(),
                e.getLastRefreshedAt()
        );
    }
}
