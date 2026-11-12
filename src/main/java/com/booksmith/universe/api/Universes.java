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

    /**
     * Todos os nomes próprios do universo: o nome dele e os nomes e apelidos de personagens, lugares e fichas do
     * mundo (religiões, idiomas, organizações...). O corretor ortográfico nunca marca estes como erro.
     */
    java.util.Set<String> vocabulary(UUID universeId);

    /** URL de uma imagem da biblioteca do universo (fora da lixeira), para a exportação embutir no livro. */
    java.util.Optional<String> imageUrl(UUID universeId, UUID imageId);

    /** Personagens e lugares do universo, com nomes, outros nomes e (para personagens) a aparência. */
    List<KnownEntity> entities(UUID universeId);

    /**
     * Resumo do universo para as sugestões de IA: o que o construtor tem preenchido, em linhas legíveis, e os
     * personagens (com o papel) e lugares. Quem chama já conferiu o dono.
     */
    Brief brief(UUID universeId);

    /**
     * @param lines      "Rótulo: valor" do construtor de universo, na ordem dos passos
     * @param characters "Lia (protagonista)"
     */
    record Brief(String name, List<String> lines, List<String> characters, List<String> places) {
    }

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
