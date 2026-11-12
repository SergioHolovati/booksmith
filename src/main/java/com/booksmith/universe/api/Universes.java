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
     * As fichas dos personagens, com os campos de texto preenchidos (personalidade, medos, história…), para a
     * revisão dos textos do livro (§4.11). O dono já foi conferido por quem chama.
     */
    List<CharacterSheet> characterSheets(UUID universeId);

    /** Resumo do universo para as sugestões de IA, sem foco em nenhum personagem. */
    default Brief brief(UUID universeId) {
        return brief(universeId, null);
    }

    /**
     * Resumo do universo para as sugestões de IA: o que o construtor tem preenchido, quem são os personagens
     * (papel, ocupação, jeito, desejo), os lugares, as relações, as fichas do mundo e a linha do tempo. Com
     * {@code focusId}, as relações desse personagem vêm primeiro e ele sai da lista dos "outros" (a ficha dele já
     * está na tela). Quem chama já conferiu o dono.
     */
    Brief brief(UUID universeId, UUID focusId);

    /**
     * @param lines      "Rótulo: valor" do construtor de universo, na ordem dos passos
     * @param characters "Lia (protagonista) — barista; jeito: curiosa…; quer: descobrir…"
     * @param places     "Café do Porto (prédio): onde tudo começa…"
     * @param relations  "Lia é irmã de Tomás: brigaram depois do incêndio"
     * @param lore       "Religiões: A Maré (os mortos voltam com a maré)"
     * @param timeline   "Ano 312 — A grande enchente: a cidade baixa ficou submersa"
     */
    record Brief(String name, List<String> lines, List<String> characters, List<String> places,
                 List<String> relations, List<String> lore, List<String> timeline) {

        public Brief(String name, List<String> lines, List<String> characters, List<String> places) {
            this(name, lines, characters, places, List.of(), List.of(), List.of());
        }
    }

    /** @param duplicateNames nomes que já existiam no universo de destino (personagens e lugares) */
    record MergeResult(List<String> duplicateNames) {
    }

    /**
     * @param kind       {@code character} ou {@code place}
     * @param appearance campo do formulário de aparência → valor e rótulo (ex.: eyeColor → green, "Verdes")
     */
    /** @param fields chave do campo → texto (só os campos de texto preenchidos, na ordem da ficha) */
    record CharacterSheet(UUID id, String name, List<String> aliases, Map<String, String> fields) {
    }

    record KnownEntity(UUID id, String kind, String name, List<String> aliases, Map<String, Trait> appearance) {
    }

    record Trait(String value, String label) {
    }
}
