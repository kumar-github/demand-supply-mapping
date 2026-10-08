package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * Mirrors engine.py's ACCESSORY_WEIGHT (Critical/Important/Minor severity per accessory). persona_id NULL = generic
 * default, applies to all personas (see the partial unique index in full_db_design.sql that protects exactly one
 * generic-default row per accessory_name).
 */
@Entity
@Table(name = "accessory_weights")
public class AccessoryWeight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "weight_id")
    private Integer weightId;

    @Column(name = "accessory_name", nullable = false)
    private String accessoryName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "persona_id")
    private Persona persona;

    @Column(name = "weight", nullable = false)
    private String weight; // Critical | Important | Minor — CHECK-constrained in the DB

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected AccessoryWeight() {
    }

    public Integer getWeightId() {
        return weightId;
    }

    public String getAccessoryName() {
        return accessoryName;
    }

    public Persona getPersona() {
        return persona;
    }

    public String getWeight() {
        return weight;
    }
}
