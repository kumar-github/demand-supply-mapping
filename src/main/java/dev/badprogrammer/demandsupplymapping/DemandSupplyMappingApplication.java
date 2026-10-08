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
// Brand fonts for the dashboard shell (Plus Jakarta Sans for headings/numbers, Inter for body) —
// swapped 2026-10-07 from Manrope/IBM Plex Sans for a cleaner, more polished look; same mechanism
// as before — @StyleSheet supports an absolute https:// URL directly, per
// https://vaadin.com/docs/latest/styling/stylesheets
@StyleSheet("https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@500;700;800&family=Inter:wght@400;500;600&display=swap")
@StyleSheet("styles.css") // Your custom styles
@Push
public class DemandSupplyMappingApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(DemandSupplyMappingApplication.class, args);
    }
}
