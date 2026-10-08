package dev.badprogrammer.demandsupplymapping.repository;

import dev.badprogrammer.demandsupplymapping.entity.AafdSupplyEnriched;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Same shape as SupplyEnrichedRepository (JpaSpecificationExecutor for the AAFD Supply tab's paged/filtered grid, plus
 * a plain findByIsActiveTrue() for anything that genuinely needs every active row) — see that interface's Javadoc. No
 * matching-engine caller exists yet for AAFD (see AafdSupplyEnriched's own Javadoc: no classification/ persona link),
 * so findByIsActiveTrue() here is currently only used by AafdRefreshService's own soft-delete pass.
 */
@Repository
public interface AafdSupplyEnrichedRepository extends JpaRepository<AafdSupplyEnriched, Long>, JpaSpecificationExecutor<AafdSupplyEnriched> {
    List<AafdSupplyEnriched> findByIsActiveTrue();
}
