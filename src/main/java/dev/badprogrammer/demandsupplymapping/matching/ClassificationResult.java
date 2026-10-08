package dev.badprogrammer.demandsupplymapping.matching;

import java.util.List;

/**
 * Mirrors the dict shape returned by engine.py's classify_supply_row() / classify_demand_row(). persona == null means
 * unclassified/parked/ out-of-scope — status always explains why.
 */
public record ClassificationResult(
        String persona,
        String subPersona,
        List<String> namedAccessories,
        String completeness,       // supply-side only: "Core only" | "Core + Partial" | "Core + Rich" | null
        Boolean frameworkConfirmed, // Java/.NET only; null = not applicable
        String status
) {
    public static ClassificationResult unclassified(String status) {
        return new ClassificationResult(null, null, List.of(), null, null, status);
    }
}
