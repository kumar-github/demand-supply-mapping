package dev.badprogrammer.demandsupplymapping.api;

import dev.badprogrammer.demandsupplymapping.api.dto.DemandDto;
import dev.badprogrammer.demandsupplymapping.entity.DemandEnriched;
import dev.badprogrammer.demandsupplymapping.repository.DemandEnrichedRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared read/filter logic over demand_enriched — same rationale and same shape as SupplyQueryService, including the
 * paged {@link #page} method added 2026-10-03 for the same grid-performance reason (see that class's Javadoc).
 */
@Service
public class DemandQueryService {

    private final DemandEnrichedRepository repository;

    public DemandQueryService(DemandEnrichedRepository repository) {
        this.repository = repository;
    }

    /**
     * Full, unpaged list — unchanged, still backs the REST API (DemandController) and the matching engine. Not used by
     * DemandView any more (now SupplyDemandView's Demand tab); see {@link #page}.
     */
    @Transactional(readOnly = true)
    public List<DemandDto> list(String persona, String subPersona, String location, String classificationStatus, boolean activeOnly) {
        List<DemandEnriched> rows = activeOnly ? repository.findByIsActiveTrue() : repository.findAll();
        return rows.stream()
                .filter(e -> persona == null || (e.getPersona() != null && persona.equalsIgnoreCase(e.getPersona().getName())))
                .filter(e -> subPersona == null || (e.getSubPersona() != null && subPersona.equalsIgnoreCase(e.getSubPersona().getName())))
                .filter(e -> location == null || Objects.equals(location, e.getLocation()))
                .filter(e -> classificationStatus == null || classificationStatus.equalsIgnoreCase(e.getClassificationStatus()))
                .map(DemandDto::from)
                .toList();
    }

    /**
     * Paged counterpart of {@link #list} — see SupplyQueryService.page's Javadoc for the full rationale.
     */
    @Transactional(readOnly = true)
    public Page<DemandDto> page(String persona, String subPersona, String location, String classificationStatus,
                                boolean activeOnly, Pageable pageable) {
        Specification<DemandEnriched> spec = buildSpecification(persona, subPersona, location, classificationStatus, activeOnly);
        return repository.findAll(spec, pageable).map(DemandDto::from);
    }

    /**
     * Row count for the current filter — see SupplyQueryService.count's Javadoc for why this is separate from
     * {@link #page}.
     */
    @Transactional(readOnly = true)
    public long count(String persona, String subPersona, String location, String classificationStatus, boolean activeOnly) {
        Specification<DemandEnriched> spec = buildSpecification(persona, subPersona, location, classificationStatus, activeOnly);
        return repository.count(spec);
    }

    private Specification<DemandEnriched> buildSpecification(String persona, String subPersona, String location,
                                                             String classificationStatus, boolean activeOnly) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (activeOnly) {
                predicates.add(cb.isTrue(root.get("isActive")));
            }
            if (persona != null) {
                predicates.add(cb.equal(cb.lower(root.join("persona").get("name")), persona.toLowerCase()));
            }
            if (subPersona != null) {
                predicates.add(cb.equal(cb.lower(root.join("subPersona").get("name")), subPersona.toLowerCase()));
            }
            if (location != null) {
                predicates.add(cb.equal(root.get("location"), location));
            }
            if (classificationStatus != null) {
                predicates.add(cb.equal(cb.lower(root.get("classificationStatus")), classificationStatus.toLowerCase()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
