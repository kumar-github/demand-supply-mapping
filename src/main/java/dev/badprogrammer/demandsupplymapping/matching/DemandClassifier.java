package dev.badprogrammer.demandsupplymapping.matching;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Java port of bench-match-cli/engine.py's classify_demand_row(). PORT STATUS (2026-09-27): ported in full,
 * branch-for-branch against the Python source (not summarized/simplified) — the demand-side parser isn't
 * persona-specific code that can be partially ported the way MatchingService.skillSignal()'s persona-family branches
 * can; the routing between branches (mobile vs Kotlin vs Java vs .NET vs frontend) has to be complete and correct to
 * even determine which persona a row belongs to. Each branch below is commented with its Python line reference at the
 * time of porting (engine.py, classify_demand_row, lines 403-563) for re-verification if engine.py changes later.
 */
public final class DemandClassifier {

    private DemandClassifier() {
    }

    private static List<String> tokenize(String text) {
        if (text == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : text.split(",")) {
            String s = t.strip().toLowerCase();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Whole-word substring match within any token (e.g. finds "java" in "Java Full Stack" but not inside
     * "javascript").
     */
    private static boolean containsWord(List<String> tokens, String word) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(word) + "\\b");
        for (String t : tokens) {
            if (p.matcher(t).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAny(Set<String> tokset, Set<String> vocab) {
        for (String v : vocab) {
            if (tokset.contains(v)) {
                return true;
            }
        }
        return false;
    }

    public static ClassificationResult classify(String skillText) {
        String raw = skillText == null ? "" : skillText.strip();
        List<String> tokens = tokenize(skillText);
        Set<String> tokset = new LinkedHashSet<>(tokens);

        // -- Whole-phrase shortcuts used verbatim in the data (lines 419-426) --
        if (raw.strip().equalsIgnoreCase("java full stack")) {
            return new ClassificationResult("Fullstack Java", null, List.of(), null, true,
                    "Reviewed — explicit persona label");
        }
        if (raw.strip().equalsIgnoreCase(".net full stack")) {
            return new ClassificationResult("Fullstack .NET", null, List.of(), null, true,
                    "Reviewed — explicit persona label");
        }

        // -- Anchor detection (lines 430-444) --
        boolean hasSpring = containsWord(tokens, "spring") || hasAny(tokset, Engine.SPRING_BOOT_TOKENS);
        boolean hasDotnetWord = tokens.stream().anyMatch(t -> t.contains(".net"))
                || tokset.contains("c#")
                || tokens.stream().anyMatch(t -> t.contains("asp.net"))
                || tokens.stream().anyMatch(t -> t.contains("vb.net"));
        boolean hasJava = containsWord(tokens, "java") || (hasSpring && !hasDotnetWord);
        boolean hasDotnet = hasDotnetWord;
        boolean hasDevops = containsWord(tokens, "devops");
        boolean hasIos = containsWord(tokens, "ios") || containsWord(tokens, "swift");
        boolean hasAndroidWord = containsWord(tokens, "android")
                || tokset.contains("native mobile app development");
        boolean hasKotlin = tokset.contains("kotlin");
        boolean hasReact = hasAny(tokset, Engine.REACT_TOKENS) || containsWord(tokens, "next.js");
        boolean hasAngular = containsWord(tokens, "angular");
        boolean hasNode = hasAny(tokset, Engine.NODE_TOKENS);
        boolean hasMernExtra = hasAny(tokset, Engine.MERN_STACK_TOKENS);
        boolean hasWebBasics = hasAny(tokset, Engine.WEB_BASICS_TOKENS);
        boolean hasLegacyDesktop = hasAny(tokset, Engine.LEGACY_DOTNET_DESKTOP);
        boolean hasDotnetFramework = hasAny(tokset, Engine.DOTNET_FRAMEWORK_TOKENS);

        // Resolve matched raw tokens to canonical display names, de-duped,
        // in sorted-token order (matches Python's sorted(tokset & NOTABLE...))
        // (lines 450-457)
        List<String> matchedTokens = new ArrayList<>();
        for (String t : tokset) {
            if (Engine.NOTABLE_ACCESSORY_TOKENS.contains(t)) {
                matchedTokens.add(t);
            }
        }
        matchedTokens.sort(String::compareTo);
        Set<String> seenCanonical = new LinkedHashSet<>();
        List<String> accessoriesDisplay = new ArrayList<>();
        for (String t : matchedTokens) {
            String name = Engine.TOKEN_TO_CANONICAL.get(t);
            if (seenCanonical.add(name)) {
                accessoriesDisplay.add(name);
            }
        }

        // -- Explicit mobile platform present (lines 460-471) --
        if (hasIos || hasAndroidWord || (hasKotlin && (hasAndroidWord || hasIos))) {
            String sub = (hasIos && !hasAndroidWord) ? "iOS"
                    : (hasAndroidWord && !hasIos) ? "Android"
                    : "iOS + Android";
            List<String> demoted = new ArrayList<>();
            if (hasJava) {
                demoted.add("Java (backend familiarity)");
            }
            if (hasDotnet) {
                demoted.add(".NET (backend familiarity)");
            }
            List<String> named = new ArrayList<>(demoted);
            named.addAll(accessoriesDisplay);
            String status = demoted.isEmpty()
                    ? "Reviewed — mobile anchor"
                    : "Reviewed — mobile anchor; language anchor demoted to named accessory";
            return new ClassificationResult("Frontend", sub, named, null, null, status);
        }

        // -- Kotlin without explicit Android/iOS (lines 474-482) --
        if (hasKotlin && !hasAndroidWord && !hasIos) {
            if (hasJava) {
                List<String> named = new ArrayList<>(List.of("Kotlin"));
                named.addAll(accessoriesDisplay);
                return new ClassificationResult("Fullstack Java", null, named, null, null,
                        "Reviewed — corrected: Kotlin does not imply Android without explicit mobile term");
            }
            return ClassificationResult.unclassified(
                    "UNCLASSIFIED — no recognizable Phase 1 anchor: '" + raw + "'");
        }

        // -- Java anchor branch (lines 485-499) --
        // 2026-09-29: anchor precedence restored to unconditional (was
        // briefly gated on has_spring/frontend-framework presence, which
        // silently misclassified demands where MAS Mapping independently
        // confirmed 'Full Stack' — see engine.py's classify_demand_row for
        // the full history). The frontend-anchor ambiguity is now surfaced
        // as a separate, standalone review flag (see frontendAnchorCheck /
        // the "Frontend Anchor Check" column) — never a silent persona
        // override. Same "checked, not used to match" principle as MAS
        // Mapping Check.
        if (hasJava) {
            List<String> named = new ArrayList<>();
            if (hasDevops) {
                named.add("DevOps");
            }
            if (tokset.contains("python")) {
                named.add("Python");
            }
            for (String a : accessoriesDisplay) {
                if (!a.equals("Python")) {
                    named.add(a);
                }
            }
            List<String> named2 = dedupPreserveOrder(named);
            return new ClassificationResult("Fullstack Java", null, named2, null, hasSpring,
                    "Reviewed — Java anchor");
        }

        // -- .NET anchor branch (lines 502-517) --
        // Routing reverted to unconditional 2026-09-29 — see above.
        if (hasDotnet) {
            if (hasLegacyDesktop) {
                return ClassificationResult.unclassified(
                        "PARKED — legacy .NET desktop (WinForms/WPF/WCF): '" + raw + "'");
            }
            List<String> named = new ArrayList<>();
            if (hasDevops) {
                named.add("DevOps");
            }
            named.addAll(accessoriesDisplay);
            List<String> named2 = dedupPreserveOrder(named);
            return new ClassificationResult("Fullstack .NET", null, named2, null, hasDotnetFramework,
                    "Reviewed — .NET anchor");
        }

        // -- Standalone frontend (lines 519-554) --
        List<String> frontendAccessoriesDisplay = new ArrayList<>();
        for (String a : accessoriesDisplay) {
            if (!a.equals("React") && !a.equals("Angular")) {
                frontendAccessoriesDisplay.add(a);
            }
        }
        if (hasReact && hasAngular) {
            return new ClassificationResult("Frontend", "React + Angular (dual-acceptable)",
                    frontendAccessoriesDisplay, null, null, "Reviewed — dual-acceptable frontend");
        }
        // MEAN must be checked before React/MERN — see engine.py comment.
        if (hasAngular && hasNode && hasMernExtra) {
            return new ClassificationResult("Frontend", "MEAN",
                    frontendAccessoriesDisplay, null, null, "Reviewed — MEAN anchor");
        }
        if (hasReact || (hasNode && hasMernExtra)) {
            return new ClassificationResult("Frontend", "React",
                    frontendAccessoriesDisplay, null, null, "Reviewed — React anchor");
        }
        if (hasAngular) {
            return new ClassificationResult("Frontend", "Angular",
                    frontendAccessoriesDisplay, null, null, "Reviewed — Angular anchor");
        }
        if (hasNode && !hasMernExtra) {
            return new ClassificationResult("Frontend", "MERN",
                    List.of("Node.js only — weak MERN match"), null, null,
                    "Reviewed — weak MERN match (Node.js only)");
        }
        if (hasWebBasics && !(hasReact || hasAngular || hasNode)) {
            return new ClassificationResult("Frontend", "Core web (framework unconfirmed)",
                    accessoriesDisplay, null, null, "Reviewed — framework-unconfirmed bucket");
        }
        if (hasDevops) {
            return ClassificationResult.unclassified(
                    "OUT OF SCOPE — DevOps-anchor persona, not a Phase 1 persona: '" + raw + "'");
        }

        boolean hitsOutOfScope = false;
        for (String t : tokset) {
            if (Engine.OUT_OF_SCOPE_TOKENS.contains(t)) {
                hitsOutOfScope = true;
                break;
            }
        }
        if (hitsOutOfScope || tokset.isEmpty()) {
            return ClassificationResult.unclassified(
                    "OUT OF SCOPE — scope leakage (Testing/Integration/Mainframe/TPM): '" + raw + "'");
        }

        return ClassificationResult.unclassified(
                "UNCLASSIFIED — no recognizable Phase 1 anchor: '" + raw + "'");
    }

    private static List<String> dedupPreserveOrder(List<String> in) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (seen.add(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
