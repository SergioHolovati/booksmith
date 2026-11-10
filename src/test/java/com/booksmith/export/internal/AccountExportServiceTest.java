package com.booksmith.export.internal;

import com.booksmith.common.data.PersonalDataArchive;
import com.booksmith.common.data.PersonalDataContributor;
import com.booksmith.manuscript.api.Manuscripts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountExportServiceTest {

    private final Manuscripts manuscripts = mock(Manuscripts.class);
    private final UUID owner = UUID.randomUUID();

    @Test
    void everyPartGoesInAndAFailedPartBecomesAWarning() throws IOException {
        PersonalDataContributor books = contributor("livros", archive -> {
            archive.json("livros/x/livro.json", Map.of("title", "A Conversão"));
            archive.text("livros/x/texto.txt", "Era uma vez");
        });
        PersonalDataContributor broken = contributor("universos", archive -> {
            throw new IllegalStateException("banco fora");
        });
        when(manuscripts.bookIds(owner)).thenReturn(List.of());
        var service = new AccountExportService(List.of(books, broken), manuscripts, null, JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-11-10T15:00:00Z"), ZoneOffset.UTC));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.write(owner, "Sérgio", out);

        Map<String, String> files = unzip(out.toByteArray());
        assertThat(files).containsKeys("livros/x/livro.json", "livros/x/texto.txt", "LEIA-ME.txt");
        assertThat(files.get("livros/x/livro.json")).contains("\"title\" : \"A Conversão\"");
        assertThat(files.get("LEIA-ME.txt")).contains("10/11/2026 às 12:00").contains("ATENÇÃO").contains("- universos");
        assertThat(service.filename()).isEqualTo("booksmith-2026-11-10.zip");
    }

    @Test
    void repeatedPathsDoNotOverwrite() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchive zip = new ZipArchive(out, JsonMapper.builder().build())) {
            zip.text("a/capa.png", "1");
            zip.text("a/capa.png", "2");
            zip.text("a/sem-extensao", "3");
            zip.text("a/sem-extensao", "4");
        }
        assertThat(unzip(out.toByteArray())).containsOnlyKeys("a/capa.png", "a/capa-2.png", "a/sem-extensao",
                "a/sem-extensao-2");
    }

    @Test
    void folderNamesAreReadableAndUnique() {
        UUID id = UUID.fromString("01a1bc28-b49d-73f9-b9ce-9f9180cb447f");
        assertThat(PersonalDataArchive.folder("A Conversão", id)).isEqualTo("a-conversao-80cb447f");
        assertThat(PersonalDataArchive.folder("  ", id)).isEqualTo("sem-titulo-80cb447f");
        // Criados no mesmo minuto: o começo do UUIDv7 é igual, o fim não
        UUID sameMinute = UUID.fromString("01a1bc28-b49d-73f9-b9ce-111111111111");
        assertThat(PersonalDataArchive.folder("A Conversão", sameMinute))
                .isNotEqualTo(PersonalDataArchive.folder("A Conversão", id));
        assertThat(PersonalDataArchive.folder("x".repeat(100), id)).hasSize(60 + 9);
    }

    private static PersonalDataContributor contributor(String name, java.util.function.Consumer<PersonalDataArchive> body) {
        return new PersonalDataContributor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void contribute(UUID ownerId, PersonalDataArchive archive) {
                body.accept(archive);
            }
        };
    }

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
