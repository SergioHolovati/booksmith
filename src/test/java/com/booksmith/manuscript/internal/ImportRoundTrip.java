package com.booksmith.manuscript.internal;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/** Para testes de outros pacotes: importa um .docx e devolve "título|parte" de cada capítulo. */
public final class ImportRoundTrip {

    private ImportRoundTrip() {
    }

    public static List<String> plan(byte[] docx) {
        var plan = ChapterSplitter.plan(DocxReader.read(docx), ChapterSplitter.Mode.AUTO, "livro.docx",
                JsonMapper.builder().build());
        return plan.chapters().stream()
                .map(d -> d.title() + "|" + (d.partTitle() == null ? "" : d.partTitle()))
                .toList();
    }
}
