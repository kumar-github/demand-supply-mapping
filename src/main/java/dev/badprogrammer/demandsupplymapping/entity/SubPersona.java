package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "sub_personas")
public class SubPersona {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sub_persona_id")
    private Integer subPersonaId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "persona_id", nullable = false)
    private Persona persona;

    @Column(name = "name", nullable = false)
    private String name;

    protected SubPersona() {
    }

    public SubPersona(Persona persona, String name) {
        this.persona = persona;
        this.name = name;
    }

    public Integer getSubPersonaId() {
        return subPersonaId;
    }

    public Persona getPersona() {
        return persona;
    }

    public String getName() {
        return name;
    }
}
