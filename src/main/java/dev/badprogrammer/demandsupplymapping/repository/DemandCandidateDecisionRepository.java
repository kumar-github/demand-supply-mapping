package dev.badprogrammer.demandsupplymapping.repository;

import dev.badprogrammer.demandsupplymapping.entity.DemandCandidateDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DemandCandidateDecisionRepository extends JpaRepository<DemandCandidateDecision, Integer> {

    List<DemandCandidateDecision> findByDemandId(String demandId);

    // Backs the pair-level upsert (UNIQUE (demand_id, employee_id) in the schema) — every write
    // goes through this lookup first, never a blind insert, so a future supply-side entry point can
    // safely read/write the exact same row.
    Optional<DemandCandidateDecision> findByDemandIdAndEmployeeId(String demandId, Long employeeId);

    List<DemandCandidateDecision> findAllByDemandIdIn(List<String> demandIds);
}
