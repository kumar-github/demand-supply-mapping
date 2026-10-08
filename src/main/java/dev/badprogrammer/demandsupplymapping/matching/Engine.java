package dev.badprogrammer.demandsupplymapping.matching;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java port of bench-match-cli/engine.py's shared constants and the persona-agnostic helper functions (band ladder,
 * location tiers, accessory canonical registry + severity table).
 * <p>
 * PORT STATUS (2026-09-27): ported in full — this file has no scope restriction to Fullstack Java, since none of
 * engine.py's constants or helpers here are persona-specific. What IS scoped to Fullstack Java for this first slice is
 * MatchingService.skillSignal()'s same-family branch — see that class's own port-status note.
 * <p>
 * Every constant/value below was copied or transcribed directly from engine.py (not re-derived from memory) —
 * cross-check against that file if this ever needs re-verifying after a future engine.py change.
 */
public final class Engine {

    // -----------------------------------------------------------------
    // Phase 1 scope constants — transcribed from classify_inputs.py (NOT
    // engine.py; kept here anyway since this is this codebase's existing
    // home for shared, persona-agnostic constants). Added 2026-09-28 for
    // RefreshService.
    // -----------------------------------------------------------------
    public static final List<String> PROPOSABLE_STATUSES = List.of(
            "Available for Deployment",
            "Blocked/Proposed for Opportunity",
            "Assignation Pending",
            "HR Action - WIP"
    );
    /**
     * Static-field compatibility shim so existing {@code Engine.PHASE1_MAS_MAPPING.contains(...)} call sites
     * (RefreshLogic, RefreshLogicVerification) keep working unchanged after the field above became settable — this
     * can't itself be reassigned, but {@code .contains(...)} always reads through to the current live value via this
     * delegating wrapper.
     */
    public static final List<String> PHASE1_MAS_MAPPING = new java.util.AbstractList<>() {
        @Override
        public String get(int index) {
            return PHASE1_MAS_MAPPING_CURRENT.get(index);
        }

        @Override
        public int size() {
            return PHASE1_MAS_MAPPING_CURRENT.size();
        }
    };
    // -----------------------------------------------------------------
    // Band ladder
    // -----------------------------------------------------------------
    public static final List<String> BAND_LADDER =
            List.of("E1.1", "E1.2", "E2.1", "E2.2", "E3.1", "E3.2");
    // -----------------------------------------------------------------
    // Location tiers
    // -----------------------------------------------------------------
    public static final Set<String> NV_CITIES =
            Set.of("Vijayawada", "Madurai", "Lucknow", "Gandhi Nagar", "Nagpur");
    // -----------------------------------------------------------------
    // Demand-side token vocabularies (only the ones actually read via
    // has_any() in classify_demand_row — see engine.py's own comment on
    // why several previously-defined token sets were removed as unused/
    // drifted-from-the-real-logic landmines. Not reintroducing them here.)
    // -----------------------------------------------------------------
    public static final Set<String> REACT_TOKENS = Set.of("react.js", "react", "next.js");
    public static final Set<String> NODE_TOKENS = Set.of("node.js", "node");
    public static final Set<String> MERN_STACK_TOKENS = Set.of("mongo db", "mongodb", "express.js");
    public static final Set<String> WEB_BASICS_TOKENS =
            Set.of("html", "css", "cascading style sheets (css)", "javascript");
    public static final Set<String> LEGACY_DOTNET_DESKTOP = Set.of("wpf", "winforms", "webforms", "wcf");
    public static final Set<String> SPRING_BOOT_TOKENS = Set.of("spring boot");
    public static final Set<String> DOTNET_FRAMEWORK_TOKENS =
            Set.of("web api", "asp.net mvc framework", "asp.net framework", "entity framework");
    public static final Set<String> OUT_OF_SCOPE_TOKENS = Set.of(
            "tricentis tosca", "etl tool - others", "solution architecture", "drupal", "php", "selenium");
    // -----------------------------------------------------------------
    // Named-accessory canonical registry — single source of truth shared
    // by both supply-side (SkillClusterRule) and demand-side (token
    // parser) accessory naming. Transcribed directly from
    // ACCESSORY_CANONICAL in engine.py.
    // -----------------------------------------------------------------
    public static final Map<String, Set<String>> ACCESSORY_CANONICAL = buildAccessoryCanonical();
    /**
     * Reverse lookup: raw token -> canonical display name.
     */
    public static final Map<String, String> TOKEN_TO_CANONICAL = buildTokenToCanonical();
    public static final Set<String> NOTABLE_ACCESSORY_TOKENS = TOKEN_TO_CANONICAL.keySet();
    // -----------------------------------------------------------------
    // Skill-weighting (Task #8) — Critical/Important/Minor severity per
    // accessory. Transcribed directly from ACCESSORY_WEIGHT in engine.py.
    // -----------------------------------------------------------------
    public static final Map<String, String> ACCESSORY_WEIGHT = buildAccessoryWeight();
    public static final Map<String, Integer> SEVERITY_RANK =
            Map.of("Critical", 3, "Important", 2, "Minor", 1);
    // -----------------------------------------------------------------
    // FRONTEND_EQUIV (build_matches.py) — React/MERN are one frontend-web
    // equivalence class (MERN's frontend half is React); Angular/MEAN are
    // their own class (MEAN's frontend half is Angular). Used by
    // MatchingService's Frontend-Frontend sub-persona-compatibility branch.
    // -----------------------------------------------------------------
    public static final Map<String, Set<String>> FRONTEND_EQUIV = Map.of(
            "React", Set.of("React", "MERN"),
            "MERN", Set.of("React", "MERN"),
            "Angular", Set.of("Angular", "MEAN"),
            "MEAN", Set.of("Angular", "MEAN"),
            "iOS", Set.of("iOS"),
            "Android", Set.of("Android")
    );
    // Case/whitespace-normalized form of PROPOSABLE_STATUSES, used by isProposableStatus() below.
    // Ported 2026-09-30 to mirror classify_inputs.py's fix for the same day: a real refreshed export
    // started writing "Blocked/Proposed for opportunity" (lowercase 'o') instead of "...Opportunity",
    // and a plain PROPOSABLE_STATUSES.contains(...) exact match silently dropped every row carrying
    // that status — 119 of 268 otherwise-eligible employees (44%), with no error, no warning, no entry
    // in any unclassified/data-issue queue. See demand-supply-mapping-requirements.md, "AFD Status
    // case-sensitivity bug" (2026-09-30), for the full writeup and Python fix this mirrors.
    private static final Set<String> PROPOSABLE_STATUSES_NORMALIZED = PROPOSABLE_STATUSES.stream()
            .map(s -> s.strip().toLowerCase())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Map<String, Integer> BAND_INDEX = buildBandIndex();
    private static final Map<String, String> CITY_ALIASES = Map.of("HFHYD", "Hyderabad");
    // Fallback only, used before RefreshService ever sets the real value (e.g. in
    // RefreshLogicVerification's standalone harness, which has no DB) — mirrors
    // classify_inputs.py's _PHASE1_MAS_MAPPING_FALLBACK exactly. The real, editable source of truth
    // is the mas_mapping_categories DB table (V7__mas_mapping_categories.sql); RefreshService calls
    // setPhase1MasMapping() with that table's active categories before every refresh run. Changed
    // 2026-10-01 from a hardcoded `public static final List.of(...)` to a settable field so the
    // active set can be changed via data (that table), not a code change — see that migration's
    // header comment, and mas_mapping_categories.json on the Python CLI side (kept in sync by hand;
    // no DB there), before changing what's active.
    private static volatile List<String> PHASE1_MAS_MAPPING_CURRENT = List.of("Full Stack", "Front End");

    private Engine() {
    }

    /**
     * True if {@code afdStatus} matches one of PROPOSABLE_STATUSES, ignoring case and leading/trailing whitespace. Use
     * this instead of {@code PROPOSABLE_STATUSES.contains(...)} — the raw list is kept public for display/reference,
     * but an exact-match filter against a source team's free-text status field is exactly what broke silently on
     * 2026-09-30 (see comment above). Null-safe: returns false for null.
     */
    public static boolean isProposableStatus(String afdStatus) {
        if (afdStatus == null) {
            return false;
        }
        return PROPOSABLE_STATUSES_NORMALIZED.contains(afdStatus.strip().toLowerCase());
    }

    public static List<String> getPhase1MasMapping() {
        return PHASE1_MAS_MAPPING_CURRENT;
    }

    public static void setPhase1MasMapping(List<String> activeCategories) {
        if (activeCategories == null || activeCategories.isEmpty()) {
            throw new IllegalArgumentException("Phase 1 MAS Mapping active set cannot be null/empty — "
                    + "refusing to silently exclude every demand/supply row.");
        }
        PHASE1_MAS_MAPPING_CURRENT = List.copyOf(activeCategories);
    }

    private static Map<String, Integer> buildBandIndex() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < BAND_LADDER.size(); i++) {
            m.put(BAND_LADDER.get(i), i);
        }
        return m;
    }

    /**
     * Returns null if outside the defined E1.1-E3.2 ladder (Task #11/#14 — E0/E2.3/anything above E3.2 is a
     * data-quality issue, not a band gap).
     */
    public static Integer bandIndex(String subBand) {
        return BAND_INDEX.get(subBand);
    }

    public static String normalizeCity(String city) {
        if (city == null) {
            return null;
        }
        String c = city.strip();
        return CITY_ALIASES.getOrDefault(c, c);
    }

    public static String locationTier(String city) {
        String c = normalizeCity(city);
        if (c == null) {
            return null;
        }
        return NV_CITIES.contains(c) ? "NV" : "Prime";
    }

    private static Map<String, Set<String>> buildAccessoryCanonical() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("Cloud", Set.of("cloud", "amazon web services (aws)", "aws",
                "microsoft azure", "azure", "google cloud platform (gcp)", "gcp"));
        m.put("DevOps", Set.of("devops", "azure devops"));
        m.put("Microservices", Set.of("microservices"));
        m.put("Kafka", Set.of("apache kafka", "kafka"));
        m.put("JMS", Set.of("jms", "java message service"));
        m.put("RabbitMQ", Set.of("rabbitmq", "rabbit mq"));
        m.put("Docker", Set.of("docker"));
        m.put("Hibernate", Set.of("hibernate"));
        m.put("Python", Set.of("python"));
        m.put("Web Services", Set.of("web services"));
        m.put("Redis", Set.of("redis"));
        m.put("GraphQL", Set.of("graphql"));
        m.put("Messaging", Set.of()); // supply-side only, no demand-side token — see engine.py comment
        m.put("React", REACT_TOKENS);
        m.put("Angular", Set.of("angular"));
        return m;
    }

    private static Map<String, String> buildTokenToCanonical() {
        Map<String, String> m = new LinkedHashMap<>();
        for (var entry : ACCESSORY_CANONICAL.entrySet()) {
            for (String token : entry.getValue()) {
                m.put(token, entry.getKey());
            }
        }
        return m;
    }

    private static Map<String, String> buildAccessoryWeight() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Framework", "Critical");
        m.put("Microservices", "Important");
        m.put("Cloud", "Important");
        m.put("DevOps", "Important");
        m.put("Kafka", "Important");
        m.put("JMS", "Important");
        m.put("RabbitMQ", "Important");
        m.put("Docker", "Minor");
        m.put("Hibernate", "Minor");
        m.put("Web Services", "Minor");
        m.put("Redis", "Minor");
        m.put("GraphQL", "Minor");
        m.put("Python", "Minor");
        m.put("Kotlin", "Minor");
        m.put("Messaging", "Minor");
        m.put("React", "Minor");
        m.put("Angular", "Minor");
        return m;
    }

    /**
     * No per-persona overrides exist in the real data today (engine.py's ACCESSORY_WEIGHT_OVERRIDES starts empty) —
     * falls straight through to the generic table, defaulting an unweighted accessory to Important (safer to
     * over-discount than silently let it through).
     */
    public static String accessorySeverity(String name, String persona) {
        return ACCESSORY_WEIGHT.getOrDefault(name, "Important");
    }
}
