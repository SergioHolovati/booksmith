package com.booksmith.export.internal;

import com.booksmith.common.data.PersonalDataArchive;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** O .zip do "Baixar tudo", escrito direto na resposta (nada fica no servidor). Caminho repetido ganha "-2". */
final class ZipArchive implements PersonalDataArchive, AutoCloseable {

    private final ZipOutputStream zip;
    private final JsonMapper jsonMapper;
    private final Set<String> paths = new HashSet<>();
    private int files;
    private long bytes;

    ZipArchive(OutputStream out, JsonMapper jsonMapper) {
        this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void json(String path, Object value) {
        bytes(path, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    @Override
    public void text(String path, String text) {
        bytes(path, text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void bytes(String path, byte[] content) {
        try {
            zip.putNextEntry(new ZipEntry(unique(path)));
            zip.write(content);
            zip.closeEntry();
            files++;
            bytes += content.length;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int files() {
        return files;
    }

    long totalBytes() {
        return bytes;
    }

    private String unique(String path) {
        if (paths.add(path)) {
            return path;
        }
        int dot = path.lastIndexOf('.');
        String base = dot > path.lastIndexOf('/') ? path.substring(0, dot) : path;
        String extension = dot > path.lastIndexOf('/') ? path.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = base + "-" + n + extension;
            if (paths.add(candidate)) {
                return candidate;
            }
        }
    }

    @Override
    public void close() {
        try {
            zip.finish();
            zip.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
