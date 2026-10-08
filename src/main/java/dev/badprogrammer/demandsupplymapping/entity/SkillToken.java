package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;

/**
 * Mirrors engine.py's token vocabularies (REACT_TOKENS, etc.) plus ACCESSORY_CANONICAL's reverse lookup, as one row per
 * (category, token).
 */
@Entity
@Table(name = "skill_tokens")
public class SkillToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Integer tokenId;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "token_text", nullable = false)
    private String tokenText;

    @Column(name = "canonical_accessory")
    private String canonicalAccessory;

    protected SkillToken() {
    }

    public SkillToken(String category, String tokenText, String canonicalAccessory) {
        this.category = category;
        this.tokenText = tokenText;
        this.canonicalAccessory = canonicalAccessory;
    }

    public Integer getTokenId() {
        return tokenId;
    }

    public String getCategory() {
        return category;
    }

    public String getTokenText() {
        return tokenText;
    }

    public String getCanonicalAccessory() {
        return canonicalAccessory;
    }
}
