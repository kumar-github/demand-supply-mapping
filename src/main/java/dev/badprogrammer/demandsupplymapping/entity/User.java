package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * PERSISTENT — see full_db_design.sql section 0. Minimal identity table, just enough to FK
 * decided_by/changed_by/assigned_reviewer against. No login/auth exists yet (V10 migration seeds one shared placeholder
 * row that DemandReviewService writes every decision as — see that migration's comment for the plan to replace it with
 * real per-user rows later).
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "email", unique = true)
    private String email;

    @Column(name = "role", nullable = false)
    private String role = "reviewer";

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected User() {
    }

    public User(String displayName, String email, String role) {
        this.displayName = displayName;
        this.email = email;
        this.role = role;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
