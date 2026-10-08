package dev.badprogrammer.demandsupplymapping.refresh;

import dev.badprogrammer.demandsupplymapping.entity.MasMappingCategory;
import dev.badprogrammer.demandsupplymapping.matching.Engine;
import dev.badprogrammer.demandsupplymapping.repository.MasMappingCategoryRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads the active/inactive MAS Mapping categories from the mas_mapping_categories table
 * (V7__mas_mapping_categories.sql) and pushes the active set into Engine.PHASE1_MAS_MAPPING via
 * Engine.setPhase1MasMapping(). Same pattern as PersonaCatalog: a thin Spring @Component sitting between the DB and the
 * dependency-free matching/refresh-logic packages, which must stay Spring/JPA-free to keep passing
 * RefreshLogicVerification's standalone harness.
 * <p>
 * No caching here, deliberately — unlike PersonaCatalog's get-or-create lookups (called per-row, thousands of times per
 * refresh), this is read once per refresh run (see RefreshService), so a category flipped in the table takes effect on
 * the very next refresh with no restart needed.
 */
@Component
public class MasMappingScope {

    private final MasMappingCategoryRepository repository;

    public MasMappingScope(MasMappingCategoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Reads the current active categories from the DB and applies them to Engine.PHASE1_MAS_MAPPING. Call this once at
     * the start of every refresh run, before RefreshLogic.filterPhase1Supply()/classifyDemand() run — see
     * RefreshService.
     */
    public void applyToEngine() {
        List<String> active = repository.findByStatus("active").stream()
                .map(MasMappingCategory::getCategory)
                .toList();
        Engine.setPhase1MasMapping(active);
    }
}
