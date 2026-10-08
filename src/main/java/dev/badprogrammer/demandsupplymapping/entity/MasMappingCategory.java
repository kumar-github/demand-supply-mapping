package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Backs the mas_mapping_categories table (V7__mas_mapping_categories.sql) — which "MAS Mapping" categories (Full Stack,
 * Front End, Tpm, Testing, ...) are currently in active Phase 1 scope, for both demand and supply. See that migration's
 * header comment for the full decision and the caution that activating a category here needs real persona/matching work
 * on top, not just a flag flip. Read via MasMappingScope, never directly by RefreshLogic (which deliberately has no
 * Spring/JPA dependency) — RefreshService calls Engine.setPhase1MasMapping() with the active set before each refresh
 * run instead.
 */
@Entity
@Table(name = "mas_mapping_categories")
public class MasMappingCategory {

    @Id
    @Column(name = "category")
    private String category;

    @Column(name = "status", nullable = false)
    private String status; // 'active' | 'inactive' — CHECK-constrained in the DB

    @Column(name = "note")
    private String note;

    protected MasMappingCategory() {
    }

    public MasMappingCategory(String category, String status, String note) {
        this.category = category;
        this.status = status;
        this.note = note;
    }

    public String getCategory() {
        return category;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
