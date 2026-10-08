package dev.badprogrammer.demandsupplymapping.repository;

import dev.badprogrammer.demandsupplymapping.entity.DecisionHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DecisionHistoryRepository extends JpaRepository<DecisionHistory, Integer> {
    List<DecisionHistory> findByDecisionIdOrderByChangedAtDesc(Integer decisionId);
}
