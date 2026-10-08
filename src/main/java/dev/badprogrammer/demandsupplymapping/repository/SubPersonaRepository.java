package dev.badprogrammer.demandsupplymapping.repository;

import dev.badprogrammer.demandsupplymapping.entity.Persona;
import dev.badprogrammer.demandsupplymapping.entity.SubPersona;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubPersonaRepository extends JpaRepository<SubPersona, Integer> {
    Optional<SubPersona> findByPersonaAndName(Persona persona, String name);
}
