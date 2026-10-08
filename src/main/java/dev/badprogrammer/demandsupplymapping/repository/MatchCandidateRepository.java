package dev.badprogrammer.demandsupplymapping.repository;

import dev.badprogrammer.demandsupplymapping.entity.MatchCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MatchCandidateRepository extends JpaRepository<MatchCandidate, Long> {
    List<MatchCandidate> findByEmployeeId(Long employeeId);

    List<MatchCandidate> findByDemandId(String demandId);
}
