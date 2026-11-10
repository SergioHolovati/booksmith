package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Leitura segura dos pacotes ZIP + XML (.docx e .odt): tamanho máximo descompactado por parte (bomba de ZIP),
 * número de entradas, e XML sem DTD nem entidades externas (XXE).
 */
final class ImportFiles {

    static final long MAX_PART_BYTES = 64L * 1024 * 1024;
    static final int MAX_ENTRIES = 5_000;

    private ImportFiles() {
    }

    static boolean isZip(byte[] file) {
        return file.length >= 4 && file[0] == 'P' && file[1] == 'K' && file[2] == 3 && file[3] == 4;
    }

    /** Lê só as partes pedidas. Arquivo que não é ZIP, ou com entradas demais, gera o erro {@code invalid}. */
    static Map<String, byte[]> unzip(byte[] file, Set<String> wanted, Supplier<ApiException> invalid) {
        Map<String, byte[]> parts = new HashMap<>();
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw invalid.get();
                }
                if (wanted.contains(entry.getName())) {
                    parts.put(entry.getName(), readLimited(zip));
                }
            }
        } catch (IOException e) {
            throw invalid.get().causedBy(e);
        }
        return parts;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_PART_BYTES) {
                throw tooLarge();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    static ApiException tooLarge() {
        return ApiException.unprocessable("import_too_large",
                "O arquivo tem texto demais para importar de uma vez. Divida-o em partes menores.");
    }

    static XMLStreamReader xml(byte[] bytes) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }

    static void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }
}
