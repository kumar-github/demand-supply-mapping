package dev.badprogrammer.demandsupplymapping.api;

import dev.badprogrammer.demandsupplymapping.api.dto.AafdSupplyDto;
import dev.badprogrammer.demandsupplymapping.entity.AafdSupplyEnriched;
import dev.badprogrammer.demandsupplymapping.repository.AafdSupplyEnrichedRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Paged/filtered read access over aafd_supply_enriched, backing the "AAFD Supply" tab of SupplyDemandView — same
 * Specification + Pageable approach as SupplyQueryService.page()/count() (one bounded DB query per grid fetch rather
 * than loading the whole table), filtering on the columns that exist and are meaningful for AAFD's shape: Band,
 * Location, Capability. There is no persona/subPersona/classificationStatus filter here (AAFD has no classification
 * logic yet — see AafdSupplyEnriched's Javadoc), and Status is AAFD's raw source column (constant "AAFD" in every real
 * row seen so far), not an analog of supply_enriched's AFD Status vocabulary, so it isn't filtered on either.
 */
@Service
public class AafdSupplyQueryService {

    private final AafdSupplyEnrichedRepository repository;

    public AafdSupplyQueryService(AafdSupplyEnrichedRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<AafdSupplyDto> page(String band, String location, String capability, boolean activeOnly, Pageable pageable) {
        Specification<AafdSupplyEnriched> spec = buildSpecification(band, location, capability, activeOnly);
        return repository.findAll(spec, pageable).map(AafdSupplyDto::from);
    }

    @Transactional(readOnly = true)
    public long count(String band, String location, String capability, boolean activeOnly) {
        Specification<AafdSupplyEnriched> spec = buildSpecification(band, location, capability, activeOnly);
        return repository.count(spec);
    }

    private Specification<AafdSupplyEnriched> buildSpecification(String band, String location, String capability, boolean activeOnly) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (activeOnly) {
                predicates.add(cb.isTrue(root.get("isActive")));
            }
            if (band != null) {
                predicates.add(cb.equal(cb.lower(root.get("band")), band.toLowerCase()));
            }
            if (location != null) {
                predicates.add(cb.equal(root.get("location"), location));
            }
            if (capability != null) {
                predicates.add(cb.equal(cb.lower(root.get("capability")), capability.toLowerCase()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
