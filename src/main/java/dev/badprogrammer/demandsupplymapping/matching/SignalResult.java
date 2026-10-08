package dev.badprogrammer.demandsupplymapping.matching;

/**
 * Mirrors the dict shape returned by band_signal()/location_signal()/ skill_signal() in build_matches.py.
 */
public record SignalResult(
        String quality,
        boolean hardExclude,
        String collapsed,
        String expanded
) {
}
