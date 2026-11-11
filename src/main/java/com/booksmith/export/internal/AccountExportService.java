package com.booksmith.export.internal;

import com.booksmith.common.data.PersonalDataArchive;
import com.booksmith.common.data.PersonalDataContributor;
import com.booksmith.manuscript.api.Manuscripts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * "Baixar tudo" (§28.2): um .zip com tudo o que é da pessoa. Cada módulo escreve a sua parte
 * ({@link PersonalDataContributor}); aqui entram o LEIA-ME e o Word de cada livro, legível em qualquer computador.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountExportService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("America/Sao_Paulo"));

    private final List<PersonalDataContributor> contributors;
    private final Manuscripts manuscripts;
    private final ExportService exportService;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** "booksmith-2026-11-10.zip". */
    public String filename() {
        return "booksmith-" + STAMP.format(Instant.now(clock)) + ".zip";
    }

    /** Escreve o .zip em {@code out}. Uma parte que falhe vira um aviso no LEIA-ME, sem perder o resto. */
    public void write(UUID ownerId, String author, OutputStream out) {
        long start = System.nanoTime();
        StringBuilder problems = new StringBuilder();
        try (ZipArchive zip = new ZipArchive(out, jsonMapper)) {
            for (PersonalDataContributor contributor : contributors) {
                try {
                    contributor.contribute(ownerId, zip);
                } catch (RuntimeException e) {
                    log.error("Baixar tudo: a parte '{}' falhou para {}", contributor.name(), ownerId, e);
                    problems.append("- ").append(contributor.name()).append('\n');
                }
            }
            try {
                words(ownerId, author, zip);
            } catch (RuntimeException e) {
                log.error("Baixar tudo: os arquivos Word falharam para {}", ownerId, e);
                problems.append("- livros em Word\n");
            }
            zip.text("LEIA-ME.txt", readme(problems.toString()));
            log.info("Baixar tudo de {}: {} arquivos, {} KB em {} ms", ownerId, zip.files(), zip.totalBytes() / 1024,
                    (System.nanoTime() - start) / 1_000_000);
        }
    }

    /** O Word de cada livro com capítulos, na pasta do livro (a mesma do livro.json). */
    private void words(UUID ownerId, String author, PersonalDataArchive archive) {
        for (UUID bookId : manuscripts.bookIds(ownerId)) {
            var content = manuscripts.bookContent(ownerId, bookId);
            if (content.chapters().isEmpty()) {
                continue;
            }
            var file = exportService.export(ownerId, author, bookId, ExportFormat.DOCX);
            archive.bytes("livros/" + PersonalDataArchive.folder(content.title(), bookId) + "/" + file.filename(),
                    file.bytes());
        }
    }

    private String readme(String problems) {
        String text = """
                Booksmith: tudo o que é seu, baixado em %s

                livros/       Uma pasta por livro:
                                - o livro em Word (.docx), pronto para abrir ou mandar para revisão;
                                - texto.txt, só o texto, capítulo a capítulo;
                                - livro.json, os dados completos (o documento de cada capítulo no formato do editor);
                                - a capa, se houver.
                universos/    Uma pasta por universo: universo.json (o construtor e o calendário), personagens,
                              lugares, mundo (religiões, governos, idiomas, organizações, criaturas, objetos e
                              termos), linha do tempo, relações, o mapa e a pasta imagens, com imagens.json
                              dizendo quem aparece em cada uma.
                conta/        Seus dados de cadastro e o perfil de autor, com a foto.
                suporte/      Os pedidos que você mandou ao suporte.

                Não entram: o que está na lixeira, o histórico de versões dos capítulos e dados internos de
                segurança (senha, códigos e sessões).

                Os .json podem ser abertos em qualquer editor de texto. Guarde este arquivo num lugar seguro: ele
                tem o seu livro inteiro.
                """.formatted(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm").withZone(ZoneId.of("America/Sao_Paulo"))
                .format(Instant.now(clock)));
        if (!problems.isEmpty()) {
            text += "\nATENÇÃO: não foi possível incluir as partes abaixo. Tente baixar de novo; se continuar, fale com o"
                    + " suporte.\n" + problems;
        }
        return text;
    }
}
