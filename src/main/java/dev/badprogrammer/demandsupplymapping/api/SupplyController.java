package dev.badprogrammer.demandsupplymapping.api;

import dev.badprogrammer.demandsupplymapping.api.dto.SupplyDto;
import dev.badprogrammer.demandsupplymapping.repository.SupplyEnrichedRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read API over supply_enriched. Filtering is delegated to {@link SupplyQueryService} (extracted 2026-09-30, task #18)
 * so the Vaadin SupplyDemandView (Supply tab) reuses the exact same in-memory filter logic in-process rather than
 * duplicating it. Filtering itself is still done in memory rather than as JPA Specifications/query derivation —
 * deliberately, for now: it's simpler to read and trust at this data volume. Worth revisiting as real database-level
 * filtering + pagination if/when scope grows (AAFD, multiple companies, etc.) past a few thousand rows.
 */
@RestController
@RequestMapping("/api/supply")
public class SupplyController {

    private final SupplyQueryService queryService;
    private final SupplyEnrichedRepository repository;

    public SupplyController(SupplyQueryService queryService, SupplyEnrichedRepository repository) {
        this.queryService = queryService;
        this.repository = repository;
    }

    @GetMapping
    public List<SupplyDto> list(
            @RequestParam(required = false) String persona,
            @RequestParam(required = false) String subPersona,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String classificationStatus,
            @RequestParam(required = false, defaultValue = "true") boolean activeOnly
    ) {
        return queryService.list(persona, subPersona, location, classificationStatus, activeOnly);
    }

    @GetMapping("/{employeeId}")
    @Transactional(readOnly = true)
    public ResponseEntity<SupplyDto> get(@PathVariable Long employeeId) {
        return repository.findById(employeeId)
                .map(SupplyDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
