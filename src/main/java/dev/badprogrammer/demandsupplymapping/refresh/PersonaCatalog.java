package dev.badprogrammer.demandsupplymapping.refresh;

import dev.badprogrammer.demandsupplymapping.entity.Persona;
import dev.badprogrammer.demandsupplymapping.entity.SubPersona;
import dev.badprogrammer.demandsupplymapping.repository.PersonaRepository;
import dev.badprogrammer.demandsupplymapping.repository.SubPersonaRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Get-or-create lookup for personas/sub_personas. Neither table is pre-seeded (full_db_design.sql defines them but has
 * no INSERT statements), and sub_persona names aren't a small fixed enum — Frontend alone produces labels like "React",
 * "Core web (framework unconfirmed)", "React + Angular (dual-acceptable)", "iOS + Android" (see
 * verification-fixtures/full_*_classified.csv for the real set) — so new rows are created lazily the first time a name
 * is seen, then cached for the rest of the JVM's life.
 * <p>
 * A blank/empty sub-persona (Fullstack Java/.NET rows, where engine.py's sub_persona is "") is treated as NO
 * sub-persona (null FK), not a row named "".
 */
@Component
public class PersonaCatalog {

    private final PersonaRepository personaRepository;
    private final SubPersonaRepository subPersonaRepository;
    private final Map<String, Persona> personaCache = new ConcurrentHashMap<>();
    private final Map<String, SubPersona> subPersonaCache = new ConcurrentHashMap<>();

    public PersonaCatalog(PersonaRepository personaRepository, SubPersonaRepository subPersonaRepository) {
        this.personaRepository = personaRepository;
        this.subPersonaRepository = subPersonaRepository;
    }

    public Persona resolvePersona(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return personaCache.computeIfAbsent(name, n ->
                personaRepository.findByName(n).orElseGet(() -> personaRepository.save(new Persona(n))));
    }

    public SubPersona resolveSubPersona(String personaName, String subPersonaName) {
        if (subPersonaName == null || subPersonaName.isBlank()) {
            return null;
        }
        Persona persona = resolvePersona(personaName);
        if (persona == null) {
            return null;
        }
        String cacheKey = personaName + "::" + subPersonaName;
        return subPersonaCache.computeIfAbsent(cacheKey, k ->
                subPersonaRepository.findByPersonaAndName(persona, subPersonaName)
                        .orElseGet(() -> subPersonaRepository.save(new SubPersona(persona, subPersonaName))));
    }
}
