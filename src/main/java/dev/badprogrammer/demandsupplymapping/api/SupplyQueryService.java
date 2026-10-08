package dev.badprogrammer.demandsupplymapping.api;

import dev.badprogrammer.demandsupplymapping.api.dto.SupplyDto;
import dev.badprogrammer.demandsupplymapping.entity.SupplyEnriched;
import dev.badprogrammer.demandsupplymapping.repository.SupplyEnrichedRepository;
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
 * Shared read/filter logic over supply_enriched, extracted out of SupplyController (2026-09-30, task #18) so the new
 * Vaadin SupplyDemandView (Supply tab) can call the exact same filtering the REST API uses, in-process, rather than
 * duplicating it or routing through HTTP to itself — per this app's architecture (Vaadin views and REST controllers
 * both call the service layer; see BenchMatchApplication's Javadoc).
 * <p>
 * {@code @Transactional(readOnly = true)} because SupplyDto.from() touches the lazy persona/subPersona associations —
 * without a transaction open here, that throws LazyInitializationException in the caller.
 */
@Service
public class SupplyQueryService {

    private final SupplyEnrichedRepository repository;

    public SupplyQueryService(SupplyEnrichedRepository repository) {
        this.repository = repository;
    }

    /**
     * Full, unpaged list — kept exactly as it was for the two callers that genuinely need every row: the REST API
     * (SupplyController, an existing external contract) and anything reading supply_enriched outside the grid. NOT used
     * by SupplyDemandView any more (see {@link #page}) — this is the method that made the Supply grid slow, because
     * every refresh pulled the entire table into memory just to display one page of it.
     */
    @Transactional(readOnly = true)
    public List<SupplyDto> list(String persona, String subPersona, String location, String classificationStatus, boolean activeOnly) {
        List<SupplyEnriched> rows = activeOnly ? repository.findByIsActiveTrue() : repository.findAll();
        return rows.stream()
                .filter(e -> persona == null || (e.getPersona() != null && persona.equalsIgnoreCase(e.getPersona().getName())))
                .filter(e -> subPersona == null || (e.getSubPersona() != null && subPersona.equalsIgnoreCase(e.getSubPersona().getName())))
                .filter(e -> location == null || Objects.equals(location, e.getLocation()))
                .filter(e -> classificationStatus == null || classificationStatus.equalsIgnoreCase(e.getClassificationStatus()))
                .map(SupplyDto::from)
                .toList();
    }

    /**
     * Paged counterpart of {@link #list}, added 2026-10-03 to fix the Supply grid being "very slow" with every record
     * on screen (reported the same day as the demand-centric export work). Pushes both the filtering AND the paging
     * down into the database via a {@code Specification} + {@code Pageable} — only one page's worth of rows (default
     * 50, see SupplyDemandView) is ever loaded into the JVM or serialized to the browser for a given fetch, instead of
     * the full supply_enriched table every time a filter changes or the grid scrolls.
     * <p>
     * Same filter semantics as {@link #list} (persona/subPersona/classificationStatus case-insensitive exact match,
     * location case-sensitive exact match, persona/subPersona null-excluded when that filter is set) so switching the
     * view over to this method is a storage-strategy change only, not a behavior change.
     */
    @Transactional(readOnly = true)
    public Page<SupplyDto> page(String persona, String subPersona, String location, String classificationStatus,
                                boolean activeOnly, Pageable pageable) {
        Specification<SupplyEnriched> spec = buildSpecification(persona, subPersona, location, classificationStatus, activeOnly);
        return repository.findAll(spec, pageable).map(SupplyDto::from);
    }

    /**
     * Row count for the current filter, without fetching any rows — the grid's lazy data view needs this separately
     * from {@link #page} (Vaadin calls the count and fetch callbacks independently), and reusing
     * {@code page(...).getTotalElements()} would run the content query just to throw the content away.
     */
    @Transactional(readOnly = true)
    public long count(String persona, String subPersona, String location, String classificationStatus, boolean activeOnly) {
        Specification<SupplyEnriched> spec = buildSpecification(persona, subPersona, location, classificationStatus, activeOnly);
        return repository.count(spec);
    }

    private Specification<SupplyEnriched> buildSpecification(String persona, String subPersona, String location,
                                                             String classificationStatus, boolean activeOnly) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (activeOnly) {
                predicates.add(cb.isTrue(root.get("isActive")));
            }
            if (persona != null) {
                // Inner join, same as list()'s "persona != null && persona.equalsIgnoreCase(...)" filter — a row
                // with no persona set is excluded when this filter is active, not matched against "null".
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
