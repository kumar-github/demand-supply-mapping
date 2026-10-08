package dev.badprogrammer.demandsupplymapping.repository;

import dev.badprogrammer.demandsupplymapping.entity.DemandEnriched;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@code JpaSpecificationExecutor} added 2026-10-03 — same rationale as SupplyEnrichedRepository's: lets
 * DemandQueryService.page(...) push the Demand grid's filtering and paging into the database instead of loading every
 * row. The List-returning finder is unchanged, still backing the REST API and the matching engine.
 */
@Repository
public interface DemandEnrichedRepository extends JpaRepository<DemandEnriched, String>, JpaSpecificationExecutor<DemandEnriched> {
    List<DemandEnriched> findByIsActiveTrue();
}
