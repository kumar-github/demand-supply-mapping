package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "personas")
public class Persona {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "persona_id")
    private Integer personaId;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    protected Persona() {
    }

    public Persona(String name) {
        this.name = name;
    }

    public Integer getPersonaId() {
        return personaId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
