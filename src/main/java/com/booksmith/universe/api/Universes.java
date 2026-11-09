package com.booksmith.universe.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O que outros módulos podem pedir ao universo: o livro cria o seu universo ao nascer (ou entra num que já
 * existe), e o agente de continuidade lê as entidades para comparar com o texto. Quem chama já conferiu o dono
 * do livro.
 */
public interface Universes {

    /** Cria um universo vazio para o usuário e devolve o id. */
    UUID create(UUID ownerId, String name);

    /** Confere que o universo existe e é do usuário; senão, 404 {@code universe_not_found}. */
    void requireOwned(UUID ownerId, UUID universeId);

    /**
     * Junta {@code from} em {@code into}: personagens, lugares e eventos da linha do tempo passam para
     * {@code into} (os eventos entram depois dos que já existiam) e {@code from} deixa de existir. Nada é
     * apagado: nomes repetidos ficam duplicados, para o autor decidir.
     */
    MergeResult merge(UUID ownerId, UUID from, UUID into);

    /** Personagens e lugares do universo, com nomes, outros nomes e (para personagens) a aparência. */
    List<KnownEntity> entities(UUID universeId);

    /** @param duplicateNames nomes que já existiam no universo de destino (personagens e lugares) */
    record MergeResult(List<String> duplicateNames) {
    }

    /**
     * @param kind       {@code character} ou {@code place}
     * @param appearance campo do formulário de aparência → valor e rótulo (ex.: eyeColor → green, "Verdes")
     */
    record KnownEntity(UUID id, String kind, String name, List<String> aliases, Map<String, Trait> appearance) {
    }

    record Trait(String value, String label) {
    }
}
