package com.booksmith.common.data;

import com.booksmith.common.text.SlugUtils;

import java.util.UUID;

/**
 * Arquivo "Baixar tudo" (§28.2): cada módulo escreve nele o que é da pessoa ({@link PersonalDataContributor}).
 * Caminhos com "/" viram pastas no .zip.
 */
public interface PersonalDataArchive {

    /** Grava um objeto como JSON legível (indentado). */
    void json(String path, Object value);

    void text(String path, String text);

    void bytes(String path, byte[] bytes);

    /**
     * Pasta de um livro ou universo: "a-conversao-cb447f3e". O fim do id (a parte aleatória do UUIDv7; o começo é o
     * horário e se repete entre itens criados no mesmo minuto) evita colisão entre títulos iguais e é o mesmo para
     * todos os módulos que escrevem na pasta.
     */
    static String folder(String name, UUID id) {
        String slug = SlugUtils.toSlug(name == null ? "" : name);
        String prefix = slug == null || slug.isBlank() ? "sem-titulo" : slug;
        if (prefix.length() > 60) {
            prefix = prefix.substring(0, 60).replaceAll("-+$", "");
        }
        String hex = id.toString();
        return prefix + "-" + hex.substring(hex.length() - 8);
    }
}
