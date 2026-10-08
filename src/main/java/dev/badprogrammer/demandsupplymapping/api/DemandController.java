package dev.badprogrammer.demandsupplymapping.api;

import dev.badprogrammer.demandsupplymapping.api.dto.DemandDto;
import dev.badprogrammer.demandsupplymapping.repository.DemandEnrichedRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read API over demand_enriched. Same in-memory-filtering caveat as SupplyController, and same delegation to a shared
 * query service ({@link DemandQueryService}) so the Vaadin SupplyDemandView (Demand tab) reuses this filtering
 * in-process.
 */
@RestController
@RequestMapping("/api/demand")
public class DemandController {

    private final DemandQueryService queryService;
    private final DemandEnrichedRepository repository;

    public DemandController(DemandQueryService queryService, DemandEnrichedRepository repository) {
        this.queryService = queryService;
        this.repository = repository;
    }

    @GetMapping
    public List<DemandDto> list(
            @RequestParam(required = false) String persona,
            @RequestParam(required = false) String subPersona,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String classificationStatus,
            @RequestParam(required = false, defaultValue = "true") boolean activeOnly
    ) {
        return queryService.list(persona, subPersona, location, classificationStatus, activeOnly);
    }

    @GetMapping("/{demandId}")
    @Transactional(readOnly = true)
    public ResponseEntity<DemandDto> get(@PathVariable String demandId) {
        return repository.findById(demandId)
                .map(DemandDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
