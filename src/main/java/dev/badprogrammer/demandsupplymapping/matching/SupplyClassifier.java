package dev.badprogrammer.demandsupplymapping.matching;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of bench-match-cli/engine.py's SUPPLY_TABLE + classify_supply_row(). PORT STATUS (2026-09-27): every entry
 * transcribed directly from engine.py, not just the Fullstack Java ones — it's an exact-match lookup table, so porting
 * only a subset would silently misclassify any supply row whose literal Skill Cluster text isn't Fullstack Java (they'd
 * fall through to "not in reference table" instead of their real persona). Persona-scoped restriction for this first
 * slice lives in MatchingService, not here.
 */
public final class SupplyClassifier {

    private static final Map<String, ClassificationResult> SUPPLY_TABLE = buildTable();

    private SupplyClassifier() {
    }

    private static Map<String, ClassificationResult> buildTable() {
        Map<String, ClassificationResult> t = new LinkedHashMap<>();

        t.put("Java, Spring Boot, REST", new ClassificationResult(
                "Fullstack Java", null, List.of(), "Core only", true, "Reviewed"));
        t.put("Java, Spring Boot, REST, Microservices", new ClassificationResult(
                "Fullstack Java", null, List.of("Microservices"), "Core + Partial", true, "Reviewed"));
        t.put("HTML, CSS, JavaScript, Redux, React.js", new ClassificationResult(
                "Frontend", "React", List.of("Redux"), "Core + Partial", null, "Reviewed"));
        t.put("HTML, CSS, Javascript, TypeScript, Angular", new ClassificationResult(
                "Frontend", "Angular", List.of("TypeScript"), "Core + Partial", null, "Reviewed"));
        t.put("HTML, CSS, Javascript, TypeSript, Angular", new ClassificationResult(
                "Frontend", "Angular", List.of("TypeScript"), "Core + Partial", null, "Reviewed (typo variant)"));
        t.put("Java, SQL", new ClassificationResult(
                "Fullstack Java", null, List.of(), "Core only", false, "Reviewed"));
        t.put("C#, .NET Framework, Microsoft SQL Server", new ClassificationResult(
                "Fullstack .NET", null, List.of(), "Core only", false, "Reviewed"));
        t.put("Java, Spring Boot, REST, React", new ClassificationResult(
                "Fullstack Java", null, List.of("React"), "Core + Partial", true, "Reviewed"));
        t.put("MongoDB, Express.js, React.js, Node.js", new ClassificationResult(
                "Frontend", "MERN", List.of(), "Core only", null, "Reviewed"));
        t.put("C#, MVC, Web API, Entity Framework, Angular, Cloud", new ClassificationResult(
                "Fullstack .NET", null, List.of("Angular", "Cloud"), "Core + Rich", true, "Reviewed"));
        t.put("iOS, Swift", new ClassificationResult(
                "Frontend", "iOS", List.of(), "Core only", null, "Reviewed"));
        t.put("Android, Kotlin, Java", new ClassificationResult(
                "Frontend", "Android", List.of("Java (backend familiarity)"), "Core only", null,
                "Reviewed — corrected (demotion, not reroute-drop)"));
        t.put("HTML, CSS, Javascript", new ClassificationResult(
                "Frontend", "Core web (framework unconfirmed)", List.of(), "Core only", null,
                "Reviewed — weak bucket"));
        t.put("Java, Spring Boot, REST, Cloud (AWS, Azure, GCP)", new ClassificationResult(
                "Fullstack Java", null, List.of("Cloud"), "Core + Partial", true, "Reviewed"));
        t.put("Java, Spring Boot, REST, Messaging", new ClassificationResult(
                "Fullstack Java", null, List.of("Messaging"), "Core + Partial", true, "Reviewed"));
        t.put("C#, MVC, Web API, Entity Framework, Cloud", new ClassificationResult(
                "Fullstack .NET", null, List.of("Cloud"), "Core + Partial", true, "Reviewed"));
        t.put("C#, SQL", new ClassificationResult(
                "Fullstack .NET", null, List.of(), "Core only", false, "Reviewed"));
        t.put("C#, Angular, Web API", new ClassificationResult(
                "Fullstack .NET", null, List.of("Angular"), "Core + Partial", true, "Reviewed"));
        t.put("C#, MVC, Web API, Entity Framework, React, DevOps", new ClassificationResult(
                "Fullstack .NET", null, List.of("React", "DevOps"), "Core + Rich", true, "Reviewed"));
        t.put("C#, Web API, Entity Framework, Microservices", new ClassificationResult(
                "Fullstack .NET", null, List.of("Microservices"), "Core + Partial", true, "Reviewed"));
        t.put("C#, MVC, Web API, Entity Framework, Angular, DevOps", new ClassificationResult(
                "Fullstack .NET", null, List.of("Angular", "DevOps"), "Core + Rich", true, "Reviewed"));
        t.put("Java, Spring Boot, REST, Angular", new ClassificationResult(
                "Fullstack Java", null, List.of("Angular"), "Core + Partial", true, "Reviewed"));
        t.put("C#, .NET Framework, Cloud, DevOps", new ClassificationResult(
                "Fullstack .NET", null, List.of("Cloud", "DevOps"), "Core + Rich", false, "Reviewed"));
        t.put("Java, Spring Boot, REST, DevOps", new ClassificationResult(
                "Fullstack Java", null, List.of("DevOps"), "Core + Partial", true, "Reviewed"));
        t.put("C#, WinForms, WPF, WCF", ClassificationResult.unclassified(
                "PARKED — legacy .NET desktop, out of Phase 1 capability"));
        t.put("C#, ASP.Net Webforms, WCF", ClassificationResult.unclassified(
                "PARKED — legacy .NET desktop, out of Phase 1 capability"));
        t.put("NFT - Performance/Accessibility | JMeter + BlazeMeter + Grafana", ClassificationResult.unclassified(
                "UNCLASSIFIED — out-of-scope persona (NFT/perf testing), Capability/MAS-Mapping mismatch"));

        return t;
    }

    public static ClassificationResult classify(String skillCluster) {
        if (skillCluster == null || skillCluster.strip().isEmpty()) {
            return ClassificationResult.unclassified(
                    "UNCLASSIFIED — Skill Cluster missing entirely (urgent data-quality flag)");
        }
        String key = skillCluster.strip();
        ClassificationResult hit = SUPPLY_TABLE.get(key);
        if (hit != null) {
            return hit;
        }
        // Exact-match-only fallback — no fuzzy matching, per agreed behavior.
        return ClassificationResult.unclassified(
                "UNCLASSIFIED — Skill Cluster not in reference table: '" + key + "'");
    }
}
