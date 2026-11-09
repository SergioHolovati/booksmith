/**
 * Manuscrito: livros e capítulos. O conteúdo de cada capítulo é um documento ProseMirror (JSON) com IDs
 * estáveis por bloco (§8 da arquitetura). Publica {@code ChapterSaved} para o agente de continuidade.
 * Cada livro vive num universo (Bíblia do Universo), criado junto com ele.
 */
@ApplicationModule(
        displayName = "Manuscrito",
        allowedDependencies = {"common", "identity :: api", "media :: api", "universe :: api"}
)
package com.booksmith.manuscript;

import org.springframework.modulith.ApplicationModule;
