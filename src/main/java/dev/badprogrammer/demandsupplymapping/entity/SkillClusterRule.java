package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * Mirrors bench-match-cli/engine.py's SUPPLY_TABLE — an exact-match lookup keyed on the literal Skill Cluster text.
 * Documentation-only until the web app's own ClassificationService is what actually reads this table (see the
 * 2026-09-27 architecture note in full_db_design.sql) — this entity IS that reader, for the Fullstack Java rows ported
 * so far.
 */
@Entity
@Table(name = "skill_cluster_rules")
public class SkillClusterRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Integer ruleId;

    @Column(name = "skill_cluster_text", nullable = false, unique = true)
    private String skillClusterText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "persona_id", nullable = false)
    private Persona persona;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_persona_id")
    private SubPersona subPersona;

    @Column(name = "anchor_skill", nullable = false)
    private String anchorSkill;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "named_accessories", columnDefinition = "text[]")
    private List<String> namedAccessories;

    @Column(name = "framework_confirmed")
    private Boolean frameworkConfirmed;

    protected SkillClusterRule() {
    }

    public Integer getRuleId() {
        return ruleId;
    }

    public String getSkillClusterText() {
        return skillClusterText;
    }

    public void setSkillClusterText(String skillClusterText) {
        this.skillClusterText = skillClusterText;
    }

    public Persona getPersona() {
        return persona;
    }

    public void setPersona(Persona persona) {
        this.persona = persona;
    }

    public SubPersona getSubPersona() {
        return subPersona;
    }

    public void setSubPersona(SubPersona subPersona) {
        this.subPersona = subPersona;
    }

    public String getAnchorSkill() {
        return anchorSkill;
    }

    public void setAnchorSkill(String anchorSkill) {
        this.anchorSkill = anchorSkill;
    }

    public List<String> getNamedAccessories() {
        return namedAccessories;
    }

    public void setNamedAccessories(List<String> namedAccessories) {
        this.namedAccessories = namedAccessories;
    }

    public Boolean getFrameworkConfirmed() {
        return frameworkConfirmed;
    }

    public void setFrameworkConfirmed(Boolean frameworkConfirmed) {
        this.frameworkConfirmed = frameworkConfirmed;
    }
}
