package dev.badprogrammer.demandsupplymapping;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.aura.Aura;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Serves both the Vaadin Flow UI and the REST API (controller package, under /api/**) from one Spring Boot
 * deployable, per the 2026-09-27 architecture decision in demand-supply-mapping-requirements.md — Vaadin views call the
 * service layer in-process; REST controllers expose the same service layer for any consumer outside the browser.
 */
@SpringBootApplication
@StyleSheet(Aura.STYLESHEET)
// Inter for everything (Bad Programmer Frost is Inter-only) — @StyleSheet supports an absolute https:// URL directly,
// per https://vaadin.com/docs/latest/styling/stylesheets
@StyleSheet("https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap")
// Bad Programmer Frost: design tokens for six themes (light, light-paper, light-aurora, dark, dark-graphite,
// dark-aurora — selected with data-theme on <html>) plus the Aura mapping. Must come before styles.css.
@StyleSheet("bp-frost.css")
@StyleSheet("styles.css") // Your custom styles
// Re-points styles.css's --bm-* variables at the Frost tokens and applies the Frost card effects. Must come last.
@StyleSheet("frost-app.css")
@Push
public class DemandSupplyMappingApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(DemandSupplyMappingApplication.class, args);
    }
}
