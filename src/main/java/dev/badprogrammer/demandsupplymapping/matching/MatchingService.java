package dev.badprogrammer.demandsupplymapping.matching;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Java port of bench-match-cli/build_matches.py's band_signal()/
 * location_signal()/skill_signal()/overall_tier()/assessment_signal()/ cap_employee_rows().
 * <p>
 * PORT STATUS (2026-09-28) — now ported in FULL against the authoritative source
 * (cli_pkg/bench-match-cli/build_matches.py — an earlier stale duplicate at /home/claude/build_matches.py was
 * mistakenly consulted for one investigation on 2026-09-28 and has since been ruled out as the reference; it is NOT
 * used here): - bandSignal(), locationSignal(), overallTier(): ported in FULL — none of these are persona-specific in
 * the Python source. - skillSignal(): both branches are now ported — the same-family Java-Java/.NET-.NET branch (from
 * the earlier pass, verified against 70,682 real Fullstack Java pairing rows), and the Frontend-Frontend
 * sub-persona-compatibility branch (added 2026-09-28, including
 * FRONTEND_EQUIV/dual-acceptable/MEAN/weak-MERN/framework-unconfirmed handling — verified against 7,254 real
 * Frontend-Frontend pairing rows). Fullstack .NET-.NET was also verified for the first time (8,901 real pairing rows) —
 * it shares this class's same-family branch with Java, but had never been exercised against real .NET data before. -
 * The cross-persona-discount concept from an OLDER version of build_matches.py (a Java/.NET employee's named frontend
 * accessory proposed at a discount against a standalone Frontend demand) is deliberately NOT ported: the current
 * authoritative source has removed it from the design entirely (see that file's module docstring — "contributed zero
 * rows to real output"), so there is nothing left to port here. An earlier investigation this session misread a stale
 * duplicate file and incorrectly reported this as a live bug in the Python pipeline; that was wrong and is corrected in
 * demand-supply-mapping-requirements.md.
 * <p>
 * Step 2 of 2026-09-29's match_candidates-parity work (build_matches.py lines 24-36, 104-115, 312-364): ported
 * assessmentSignal(), ageingRank(), and capEmployeeRows() — RATING_RANK/RATING_DISPLAY/AGEING_RANK and the per-employee
 * row cap (all Strong kept, top-3 Good by urgency, Weak only as a fallback when an employee has zero Strong/Good, top-3
 * Excluded near-misses). NOT wired to the database yet — that's step 3 (MatchingRunService), which will call these
 * against real supply/demand rows and persist the result to match_candidates.
 */
@Service
public class MatchingService {

    private static final String FRAMEWORK_UNCONFIRMED = "Core web (framework unconfirmed)";
    private static final Map<String, Integer> RATING_RANK = Map.of(
            "Expert", 6, "Proficient", 5, "Competent", 4, "Advanced Beginner", 3,
            "Beginner", 2, "Entry", 1, "0", 0, "0% Score", 0
    );
    /**
     * Both keys mean "not yet assessed" — RATING_DISPLAY in the Python source.
     */
    private static final Set<String> UNASSESSED_RATINGS = Set.of("0", "0% Score");
    private static final Map<Integer, String> RATING_QUALIFIER = Map.of(
            6, "Excellent", 5, "Strong", 4, "Moderate", 3, "Moderate", 2, "Light", 1, "Light", 0, ""
    );
    private static final Map<String, Integer> AGEING_RANK = Map.of(
            "Overdue Till Aug'26", 0, "Due in Sep'26", 1, "Future Due in Oct'26", 2,
            "Future Due in Nov'26", 3, "Future Due in Dec'26", 4,
            "Future Due in Jan'27", 5, "Beyond JAS'26", 6
    );
    private static final int MAX_GOOD_PER_EMPLOYEE = 3;

    // -----------------------------------------------------------------
    // assessment_signal (build_matches.py lines 24-30, 104-115) —
    // ported in full.
    // -----------------------------------------------------------------
    private static final int MAX_WEAK_FALLBACK_PER_EMPLOYEE = 10;
    private static final int MAX_EXCLUDED_PER_EMPLOYEE = 3;
    /**
     * Cap on the separate "below_one"/Override-eligible bucket carved out of capEmployeeRows() below — kept small for
     * the same reason MAX_EXCLUDED_PER_EMPLOYEE is, just a sanity bound against a pathological persona/demand mix, not
     * a tuned business number.
     */
    private static final int MAX_BAND_OVERRIDE_PER_EMPLOYEE = 5;
    /**
     * A Java/.NET/Frontend "still fairly close" skill miss — same three-way split cap_employee_rows()'s miss_distance()
     * uses, build_matches.py lines 351-360.
     */
    private static final Set<String> CLOSE_SKILL_MISS_QUALITIES = Set.of(
            "core", "accessory_gap_critical", "accessory_gap_important", "accessory_gap_minor"
    );

    // -----------------------------------------------------------------
    // band_signal (build_matches.py lines 66-89) — ported in full
    // -----------------------------------------------------------------
    public SignalResult bandSignal(String empSubBand, String demSubBand) {
        Integer ei = Engine.bandIndex(empSubBand);
        Integer di = Engine.bandIndex(demSubBand);
        if (ei == null || di == null) {
            return new SignalResult("unresolved", false,
                    "Band ladder position unclear",
                    "Employee " + empSubBand + " vs demand " + demSubBand
                            + " — outside the defined E1.1-E3.2 ladder (parked item, needs manual check)");
        }
        int diff = ei - di;
        if (diff < 0) {
            // Kept as an exact, unmodified port of build_matches.py's band_signal() — "below"
            // covers ANY amount below, with no quality-level distinction between one sub-band
            // below and two-or-more. Deliberately NOT split here even though the Review
            // interaction model's Override action only applies to the one-below case: splitting
            // this method's own quality/collapsed/expanded text would change what
            // ShortlistWorkbookService's Excel export shows and what MatchingRunVerification
            // diffs against the Python ground truth, for zero benefit — see isOneBandBelow()
            // below, which answers the one-below question as an orthogonal, additive check
            // instead of touching this port.
            return new SignalResult("below", true,
                    "Below the ask — excluded",
                    "Employee " + empSubBand + " is below demand's " + demSubBand + " — hard exclude");
        }
        if (diff >= 2) {
            return new SignalResult("too_senior", true,
                    "Two+ levels above — excluded",
                    "Employee " + empSubBand + " is " + diff + " levels above demand's " + demSubBand
                            + " — hard exclude");
        }
        if (diff == 1) {
            return new SignalResult("one_above", false,
                    "One band above the ask",
                    "Employee " + empSubBand + " vs demand " + demSubBand + " (one level above)");
        }
        return new SignalResult("exact", false,
                "Exact band match",
                "Employee " + empSubBand + " = demand " + demSubBand + " (exact)");
    }

    /**
     * NOT part of the build_matches.py port — a new, additive, Java-only check for the Review interaction model's
     * Override action ("a distinct, deliberate action specifically for the 'below policy' tier (one-band-below
     * candidates)"). Deliberately kept separate from {@link #bandSignal} itself (see that method's comment) so this new
     * business rule can never perturb the ported quality/collapsed/expanded text that the Excel export and
     * MatchingRunVerification's ground-truth parity check both depend on. Returns false for an unresolved band ladder
     * position, same as bandSignal() would.
     */
    public boolean isOneBandBelow(String empSubBand, String demSubBand) {
        Integer ei = Engine.bandIndex(empSubBand);
        Integer di = Engine.bandIndex(demSubBand);
        return ei != null && di != null && ei - di == -1;
    }

    // -----------------------------------------------------------------
    // AGEING_RANK (build_matches.py lines 32-36) — ported in full.
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // location_signal (build_matches.py lines 92-101) — ported in full
    // -----------------------------------------------------------------
    public SignalResult locationSignal(String empCity, String demCity, String panIndia) {
        String ec = Engine.normalizeCity(empCity);
        String dc = Engine.normalizeCity(demCity);
        boolean same = ec != null && dc != null && ec.equals(dc);
        String note = "Yes".equals(panIndia)
                ? " (Pan India = Yes flagged on this demand — may mean no location constraint; unconfirmed)"
                : "";
        if (same) {
            return new SignalResult("same_city", false, "Same city",
                    "Employee in " + ec + " (" + Engine.locationTier(ec) + "); demand in " + dc
                            + " (" + Engine.locationTier(dc) + ") — same city" + note);
        }
        return new SignalResult("diff_city", false, "Different city",
                "Employee in " + ec + " (" + Engine.locationTier(ec) + "); demand in " + dc
                        + " (" + Engine.locationTier(dc) + ") — different city, still viable" + note);
    }

    /**
     * framework_confirmed==null means "not applicable to this persona" and must NOT be treated as a penalty — only
     * explicit false counts (build_matches.py's _framework_confirmed, lines 118-127).
     */
    private boolean frameworkConfirmed(Boolean v) {
        return v == null || v;
    }

    // -----------------------------------------------------------------
    // cap_employee_rows (build_matches.py lines 312-364) — ported in
    // full, including its own docstring's stated rationale (Strong/Good
    // both "propose freely"; Weak only when neither exists at all;
    // Excluded near-misses shown regardless, as a different signal).
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // skill_signal — Java-Java / .NET-.NET branch only (build_matches.py
    // lines 140-193). See class-level PORT STATUS note for what's not
    // ported yet (Frontend-Frontend).
    // -----------------------------------------------------------------
    public SignalResult skillSignal(ClassificationResult emp, ClassificationResult dem) {
        String eP = emp.persona();
        String dP = dem.persona();

        boolean sameJavaOrDotnet =
                ("Fullstack Java".equals(eP) || "Fullstack .NET".equals(eP)) && dP != null && dP.equals(eP);

        if (sameJavaOrDotnet) {
            List<Object[]> gaps = new ArrayList<>(); // [name, severity]

            if (frameworkConfirmed(dem.frameworkConfirmed()) && !frameworkConfirmed(emp.frameworkConfirmed())) {
                String fwName = "Fullstack Java".equals(eP) ? "Spring Boot" : "Web API / MVC / Entity Framework";
                gaps.add(new Object[]{fwName, Engine.accessorySeverity("Framework", eP)});
            }

            List<String> dAccess = dem.namedAccessories() == null ? List.of() : dem.namedAccessories();
            List<String> eAccess = emp.namedAccessories() == null ? List.of() : emp.namedAccessories();
            if (!dAccess.isEmpty()) {
                Set<String> have = eAccess.stream().map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
                for (String a : dAccess) {
                    if (!have.contains(a.toLowerCase())) {
                        gaps.add(new Object[]{a, Engine.accessorySeverity(a, eP)});
                    }
                }
            }

            String compNote = switch (String.valueOf(emp.completeness())) {
                case "Core only" -> "core skills confirmed, thinner profile";
                case "Core + Partial" -> "core plus some named extras — solid profile";
                case "Core + Rich" -> "core plus several named extras — well-rounded profile";
                default -> "profile detail unavailable";
            };

            if (gaps.isEmpty()) {
                return new SignalResult("core", false,
                        "Persona match (" + eP + ") — " + compNote,
                        "Employee: " + eP + ", completeness=" + emp.completeness()
                                + ", named accessories=" + (eAccess.isEmpty() ? "none" : eAccess)
                                + ". Demand: " + dP + ", named accessories asked="
                                + (dAccess.isEmpty() ? "none" : dAccess) + ".");
            }

            Object[] worst = gaps.get(0);
            for (Object[] g : gaps) {
                if (Engine.SEVERITY_RANK.get((String) g[1]) > Engine.SEVERITY_RANK.get((String) worst[1])) {
                    worst = g;
                }
            }
            String worstSeverity = (String) worst[1];
            StringBuilder gapList = new StringBuilder();
            for (int i = 0; i < gaps.size(); i++) {
                if (i > 0) gapList.append(", ");
                gapList.append(gaps.get(i)[0]).append(" (").append(gaps.get(i)[1]).append(")");
            }
            String quality = switch (worstSeverity) {
                case "Critical" -> "accessory_gap_critical";
                case "Important" -> "accessory_gap_important";
                default -> "accessory_gap_minor";
            };
            String tierWord = switch (worstSeverity) {
                case "Critical" -> "Weak";
                case "Important" -> "capped at Good";
                default -> "no cap";
            };
            String collapsed = "Persona match (" + eP + ") — " + compNote + "; gap: " + gapList + " — " + tierWord;
            return new SignalResult(quality, false, collapsed,
                    "Employee: " + eP + ", completeness=" + emp.completeness()
                            + ", named accessories=" + (eAccess.isEmpty() ? "none" : eAccess)
                            + ", framework_confirmed=" + frameworkConfirmed(emp.frameworkConfirmed())
                            + ". Demand: " + dP + ", named accessories asked=" + (dAccess.isEmpty() ? "none" : dAccess)
                            + ", framework_confirmed=" + frameworkConfirmed(dem.frameworkConfirmed())
                            + ". Missing/gap items with severity: " + gapList
                            + ". Worst severity (" + worstSeverity + ") decides the tier cap "
                            + "(Task #8 skill-weighting, v1 — weights are correctable).");
        }

        if ("Frontend".equals(eP) && "Frontend".equals(dP)) {
            String eSub = emp.subPersona();
            String dSub = dem.subPersona();

            if (dSub != null && dSub.contains("dual-acceptable")) {
                if (eSub != null && (eSub.equals("React") || eSub.equals("Angular")
                        || eSub.equals("MERN") || eSub.equals("MEAN"))) {
                    return new SignalResult("core", false,
                            "Persona match — Frontend/" + eSub + ", demand is dual-acceptable (React/Angular)",
                            "Employee sub-persona: " + eSub + ". Demand accepts either React or Angular "
                                    + "(dual-acceptable, tagged exception).");
                }
                if (FRAMEWORK_UNCONFIRMED.equals(eSub)) {
                    return new SignalResult("framework_unconfirmed", false,
                            "Weak — framework unconfirmed on employee side, matched at discount against a dual-acceptable demand",
                            "Employee: HTML/CSS/JS with no framework named. Demand accepts React or Angular. Matched at discount.");
                }
                return new SignalResult("wrong_subpersona", true,
                        "Wrong sub-persona — employee is " + eSub + ", demand is dual-acceptable React/Angular but employee's specialty ("
                                + eSub + ") doesn't apply",
                        "Employee sub-persona " + eSub + " vs demand's dual-acceptable React/Angular — not a fit (e.g. iOS/Android vs web frameworks).");
            }

            if (FRAMEWORK_UNCONFIRMED.equals(eSub) || FRAMEWORK_UNCONFIRMED.equals(dSub)) {
                if (Objects.equals(eSub, dSub)) {
                    return new SignalResult("framework_unconfirmed", false,
                            "Weak — framework unconfirmed on both sides",
                            "Both employee and demand only name HTML/CSS/JS core web skills with no framework specified.");
                }
                String otherSub = FRAMEWORK_UNCONFIRMED.equals(eSub) ? dSub : eSub;
                if (otherSub != null && (otherSub.equals("React") || otherSub.equals("Angular")
                        || otherSub.equals("MERN") || otherSub.equals("MEAN"))) {
                    return new SignalResult("framework_unconfirmed", false,
                            "Weak — framework unconfirmed side matched at discount against " + otherSub,
                            "One side is core-web/framework-unconfirmed, other side is " + otherSub
                                    + ". Matched at discount, not a confirmed specialist match.");
                }
                return new SignalResult("wrong_subpersona", true, "Wrong sub-persona",
                        eSub + " vs " + dSub + " — not compatible.");
            }

            if ("MERN".equals(dSub) && dem.status() != null && dem.status().toLowerCase().contains("weak")) {
                if (eSub != null && (eSub.equals("MERN") || eSub.equals("React") || eSub.equals("MEAN"))) {
                    return new SignalResult("weak_node", false,
                            "Weak — demand only names Node.js (incomplete MERN signal); employee is " + eSub,
                            "Demand names only the Node.js backend runtime, no UI framework — a weak/incomplete MERN signal.");
                }
                return new SignalResult("wrong_subpersona", true, "Wrong sub-persona",
                        eSub + " vs weak-MERN(Node.js only) demand.");
            }

            Set<String> eEquiv = Engine.FRONTEND_EQUIV.getOrDefault(eSub, Set.of(eSub));
            if ((dSub != null && eEquiv.contains(dSub)) || Objects.equals(eSub, dSub)) {
                String compNote = switch (String.valueOf(emp.completeness())) {
                    case "Core only" -> "core skills confirmed, thinner profile";
                    case "Core + Partial" -> "core plus some named extras — solid profile";
                    case "Core + Rich" -> "core plus several named extras — well-rounded profile";
                    default -> "profile detail unavailable";
                };
                return new SignalResult("core", false,
                        "Persona match — Frontend/" + eSub + " vs Frontend/" + dSub + " — " + compNote,
                        "Employee: Frontend/" + eSub + " (completeness=" + emp.completeness() + "). Demand: Frontend/" + dSub + ".");
            }
            return new SignalResult("wrong_subpersona", true,
                    "Wrong sub-persona — employee is " + eSub + ", demand needs " + dSub,
                    "Employee sub-persona " + eSub + " does not satisfy demand sub-persona " + dSub
                            + " — the exact false-positive the sub-persona split guards against.");
        }

        return new SignalResult("no_relation", true, "Not relevant", "");
    }

    // -----------------------------------------------------------------
    // mas_mapping_cross_check (build_matches.py, added 2026-10-01) — a
    // pairing-level reviewer flag, distinct from RefreshLogic's existing
    // masMappingCheck()/masMappingNote(): those compare a demand row's OWN
    // raw MAS Mapping against the persona the ENGINE derived for that same
    // row. This instead compares the demand's raw MAS Mapping directly
    // against the MATCHED supply employee's own raw MAS Mapping — two
    // independently filled-in source labels (one from the demand team, one
    // from HR/the bench team) that can disagree with each other even when
    // each individually agrees with its own side's engine-derived persona,
    // a case neither existing check can see since neither looks across the
    // pairing. Check-only, same as every other MAS Mapping flag in this
    // project — never used to filter/score. Lives here (not RefreshLogic)
    // because, like skillSignal/bandSignal/locationSignal above, it needs
    // both sides of a pairing, not a single row.
    //
    // Only meaningful now that both sides are scoped to the same active MAS
    // Mapping categories (the 2026-10-01 scope gate) — see
    // build_matches.py's mas_mapping_cross_check() docstring for the full
    // reasoning. masMappingRaw on both SupplyEnriched and DemandEnriched is
    // already trimmed/null-normalized by RefreshLogic.nz() at ingestion
    // time, so no further normalization is needed here beyond a defensive
    // strip — kept anyway for exact parity with the Python side, which does
    // its own independent stripping.
    // -----------------------------------------------------------------
    public boolean masMappingCrossCheck(String empMasMapping, String demMasMapping) {
        if (empMasMapping == null || demMasMapping == null) {
            return false; // nothing to compare — one side has no MAS Mapping on record
        }
        return !empMasMapping.strip().equals(demMasMapping.strip());
    }

    public String masMappingCrossCheckNote(String empMasMapping, String demMasMapping) {
        if (!masMappingCrossCheck(empMasMapping, demMasMapping)) {
            return null;
        }
        return "Employee MAS Mapping = '" + empMasMapping.strip() + "' but demand MAS Mapping = '"
                + demMasMapping.strip() + "'";
    }

    // -----------------------------------------------------------------
    // overall_tier (build_matches.py lines 262-290) — ported in full,
    // persona-agnostic.
    // -----------------------------------------------------------------
    public String overallTier(SignalResult skill, SignalResult band, SignalResult loc) {
        if (skill.hardExclude() || band.hardExclude()) {
            return "Excluded";
        }
        String sq = skill.quality();
        int discounts = 0;
        if ("one_above".equals(band.quality())) {
            discounts++;
        }
        if ("diff_city".equals(loc.quality())) {
            discounts++;
        }
        if ("unresolved".equals(band.quality())) {
            discounts++;
        }

        if (sq.equals("framework_unconfirmed") || sq.equals("weak_node") || sq.equals("accessory_gap_critical")) {
            return "Weak";
        }
        if (sq.equals("accessory_gap_important")) {
            return discounts <= 1 ? "Good" : "Weak";
        }
        if (sq.equals("core") || sq.equals("accessory_gap_minor")) {
            if (discounts == 0) return "Strong";
            if (discounts == 1) return "Good";
            return "Weak";
        }
        return "Weak";
    }

    /**
     * rating may be null here (a blank/NA-token supply Rating cell — RefreshLogic.nz() already normalizes those to
     * null). Python's source reads rating straight from a pandas cell and does `r = str(rating)`; a missing cell there
     * is a float NaN, and str(NaN) == "nan" — which isn't a RATING_RANK/RATING_DISPLAY key either, so it falls through
     * to the same rank-0 "not recognized" branch that "nan" (the literal string) does here.
     */
    public AssessmentResult assessmentSignal(String rating, Double score) {
        String r = rating == null ? "nan" : rating;
        String scoreStr = score == null ? "nan" : String.format("%.3f", score);
        if (UNASSESSED_RATINGS.contains(r)) {
            return new AssessmentResult(RATING_RANK.get(r), "Not yet assessed",
                    "No usable score/rating on file (score=" + scoreStr + ")");
        }
        int rank = RATING_RANK.getOrDefault(r, 0);
        String qualifier = RATING_QUALIFIER.getOrDefault(rank, "");
        String collapsed = qualifier.isEmpty() ? r : r + " — " + qualifier;
        return new AssessmentResult(rank, collapsed, "Rating: " + r + "; raw score: " + scoreStr);
    }

    /**
     * AGEING_RANK.get(dem.get('Due Category_New'), 9) — build_matches.py line 423.
     */
    public int ageingRank(String dueCategory) {
        return AGEING_RANK.getOrDefault(dueCategory, 9);
    }

    public List<MatchRow> capEmployeeRows(List<MatchRow> empRows) {
        List<MatchRow> strong = empRows.stream().filter(r -> "Strong".equals(r.overallTier())).toList();
        List<MatchRow> good = empRows.stream().filter(r -> "Good".equals(r.overallTier())).toList();
        List<MatchRow> weak = empRows.stream().filter(r -> "Weak".equals(r.overallTier())).toList();
        // One-sub-band-below rows are split out of the generic Excluded near-miss bucket below —
        // it's a deliberate, named Override-eligible case (see isOneBandBelow()'s Javadoc), not a
        // skill near-miss, so it gets its own uncrowded cap/sort rather than competing with
        // ordinary Excluded near-misses for MAX_EXCLUDED_PER_EMPLOYEE slots.
        List<MatchRow> bandOverrideEligible = empRows.stream()
                .filter(r -> "Excluded".equals(r.overallTier()) && r.bandOverrideEligible()).toList();
        List<MatchRow> excluded = empRows.stream()
                .filter(r -> "Excluded".equals(r.overallTier()) && !r.bandOverrideEligible()).toList();

        // sorted(..., key=lambda r: (r['ageing_rank'], -r['balance_positions']))
        // balance_positions is null-safe here (treated as 0) as a defensive
        // addition beyond the strict port — the Python source would raise a
        // TypeError comparing None, since real approved-demand rows always
        // carry a Balance Positions value in practice.
        Comparator<MatchRow> byUrgencyThenOpenPositions = Comparator
                .<MatchRow>comparingInt(MatchRow::ageingRank)
                .thenComparingInt(r -> -(r.balancePositions() == null ? 0 : r.balancePositions()));

        List<MatchRow> goodKept = good.stream()
                .sorted(byUrgencyThenOpenPositions)
                .limit(MAX_GOOD_PER_EMPLOYEE)
                .toList();

        List<MatchRow> weakKept;
        if (strong.isEmpty() && good.isEmpty()) {
            weakKept = weak.stream()
                    .sorted(byUrgencyThenOpenPositions)
                    .limit(MAX_WEAK_FALLBACK_PER_EMPLOYEE)
                    .toList();
        } else {
            weakKept = List.of();
        }

        Comparator<MatchRow> byMissDistanceThenUrgency = Comparator
                .<MatchRow>comparingInt(this::missDistance)
                .thenComparingInt(MatchRow::ageingRank);
        List<MatchRow> excludedKept = excluded.stream()
                .sorted(byMissDistanceThenUrgency)
                .limit(MAX_EXCLUDED_PER_EMPLOYEE)
                .toList();

        List<MatchRow> bandOverrideKept = bandOverrideEligible.stream()
                .sorted(byUrgencyThenOpenPositions)
                .limit(MAX_BAND_OVERRIDE_PER_EMPLOYEE)
                .toList();

        List<MatchRow> result = new ArrayList<>(strong);
        result.addAll(goodKept);
        result.addAll(weakKept);
        result.addAll(excludedKept);
        result.addAll(bandOverrideKept);
        return result;
    }

    private int missDistance(MatchRow r) {
        return CLOSE_SKILL_MISS_QUALITIES.contains(r.skillQuality()) ? 1 : 2;
    }
}
