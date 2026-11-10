package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proteção contra "zip bomb" na importação (.docx e .odt são ZIP): um arquivo pequeno que se expande para
 * gigabytes, ou com milhares de entradas, para derrubar o servidor. A proteção existe; este teste garante que
 * nenhuma refatoração a perca.
 */
class ImportFilesTest {

    private static final ApiException INVALID = ApiException.unprocessable("invalid", "inválido");

    @Test
    void partThatExpandsBeyondTheLimitIsRefused() throws IOException {
        // ~65 MB de zeros comprimem para poucos KB: o tamanho do arquivo não denuncia o ataque
        byte[] bomb = zip(1, ImportFiles.MAX_PART_BYTES + 1024 * 1024);
        assertThat(bomb.length).isLessThan(1024 * 1024);

        assertThatThrownBy(() -> ImportFiles.unzip(bomb, Set.of("part-0.xml"), () -> INVALID))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("import_too_large"));
    }

    @Test
    void tooManyEntriesIsRefused() throws IOException {
        byte[] many = zip(ImportFiles.MAX_ENTRIES + 1, 1);
        assertThatThrownBy(() -> ImportFiles.unzip(many, Set.of("part-0.xml"), () -> INVALID)).isSameAs(INVALID);
    }

    @Test
    void normalFileIsRead() throws IOException {
        assertThat(ImportFiles.unzip(zip(3, 100), Set.of("part-1.xml"), () -> INVALID)).containsOnlyKeys("part-1.xml");
    }

    private static byte[] zip(int entries, long bytesPerEntry) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] chunk = new byte[64 * 1024];
            for (int i = 0; i < entries; i++) {
                zip.putNextEntry(new ZipEntry("part-" + i + ".xml"));
                for (long written = 0; written < bytesPerEntry; written += chunk.length) {
                    zip.write(chunk, 0, (int) Math.min(chunk.length, bytesPerEntry - written));
                }
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
