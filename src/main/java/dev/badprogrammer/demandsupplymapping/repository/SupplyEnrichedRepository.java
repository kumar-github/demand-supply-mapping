package dev.badprogrammer.demandsupplymapping.repository;

import dev.badprogrammer.demandsupplymapping.entity.SupplyEnriched;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@code JpaSpecificationExecutor} added 2026-10-03 so {@code SupplyQueryService.page(...)} can push the Supply grid's
 * filtering AND paging down into the database (one bounded query per page) instead of
 * {@code findAll()}/{@code findByIsActiveTrue()} loading the entire table into memory on every grid refresh — see that
 * method's Javadoc for why the view was slow without it. The two {@code List}-returning finders below are unchanged and
 * still back the REST API's full-list contract (SupplyController) and the matching engine, which both genuinely need
 * every row, not a page of it.
 */
@Repository
public interface SupplyEnrichedRepository extends JpaRepository<SupplyEnriched, Long>, JpaSpecificationExecutor<SupplyEnriched> {
    List<SupplyEnriched> findByIsActiveTrue();
}
