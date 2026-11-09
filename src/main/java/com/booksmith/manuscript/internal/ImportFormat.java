package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Formatos que a importação aceita. O formato vem do conteúdo, não só do nome: um .docx renomeado para .doc
 * continua sendo lido, e um .doc de verdade (binário antigo) recebe uma explicação de como converter.
 */
enum ImportFormat {

    DOCX, ODT, MARKDOWN, TEXT;

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "text");
    private static final Set<String> MARKDOWN_EXTENSIONS = Set.of("md", "markdown");

    static ImportedText.Result read(byte[] file, String fileName) {
        return switch (detect(file, fileName)) {
            case DOCX -> DocxReader.read(file);
            case ODT -> OdtReader.read(file);
            case MARKDOWN -> TextReader.markdown(TextReader.decode(file));
            case TEXT -> TextReader.plain(TextReader.decode(file));
        };
    }

    static ImportFormat detect(byte[] file, String fileName) {
        String extension = extension(fileName);
        if (ImportFiles.isZip(file)) {
            Map<String, byte[]> parts = ImportFiles.unzip(file, Set.of("mimetype", "word/document.xml"),
                    () -> invalidFor(extension));
            if (parts.containsKey("word/document.xml")) {
                return DOCX;
            }
            String mimetype = parts.containsKey("mimetype")
                    ? new String(parts.get("mimetype"), StandardCharsets.US_ASCII).strip() : "";
            if (mimetype.startsWith(OdtReader.MIMETYPE)) {
                return ODT;
            }
            if (mimetype.startsWith("application/vnd.oasis.opendocument.")) {
                throw ApiException.unprocessable("import_unsupported_format",
                        "Este arquivo é uma planilha ou apresentação. Importe um documento de texto (.odt ou .docx).");
            }
            throw extension.equals("docx") || extension.equals("odt") ? invalidFor(extension) : unsupported();
        }
        if (startsWith(file, 0x25, 0x50, 0x44, 0x46)) { // %PDF
            throw ApiException.unprocessable("import_unsupported_format",
                    "PDF não dá para importar: ele guarda páginas, não parágrafos, e o texto sairia quebrado. "
                            + "Se você tem o original (Word, Google Docs, LibreOffice), baixe em .docx e importe esse.");
        }
        if (startsWith(file, 0xD0, 0xCF, 0x11, 0xE0)) { // .doc do Word 97–2003
            throw ApiException.unprocessable("import_unsupported_format",
                    "Este é um .doc, o formato antigo do Word. Abra no Word ou no Google Docs e use \"Salvar como\" "
                            + "em .docx; depois importe o novo arquivo.");
        }
        if (startsWith(file, '{', '\\', 'r', 't', 'f')) {
            throw ApiException.unprocessable("import_unsupported_format",
                    "Arquivos .rtf ainda não são importados. Abra no Word, no LibreOffice ou no WordPad e salve "
                            + "em .docx ou .odt.");
        }
        if (extension.equals("docx") || extension.equals("odt")) {
            throw invalidFor(extension);
        }
        if (MARKDOWN_EXTENSIONS.contains(extension)) {
            return MARKDOWN;
        }
        if (extension.isEmpty() || TEXT_EXTENSIONS.contains(extension)) {
            return TEXT;
        }
        throw unsupported();
    }

    static ApiException unsupported() {
        return ApiException.unprocessable("import_unsupported_format",
                "O Booksmith importa documentos do Word (.docx), do LibreOffice (.odt), texto (.txt) e Markdown (.md).");
    }

    private static ApiException invalidFor(String extension) {
        return extension.equals("odt") ? OdtReader.invalid() : DocxReader.notDocx();
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).strip().toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] file, int... prefix) {
        if (file.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((file[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
