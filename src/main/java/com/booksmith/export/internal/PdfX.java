package com.booksmith.export.internal;

import com.booksmith.common.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * PDF para gráfica: o PDF do livro (em RGB, cores de tela) vira <b>PDF/X-1a em CMYK</b>, com o perfil ISO Coated v2
 * (ECI, FOGRA39) como OutputIntent. A conversão é do Ghostscript (pdfwrite), rodando como programa separado:
 * <ul>
 *   <li>só com PDFs que a própria API gerou (nunca um arquivo enviado pelo usuário);</li>
 *   <li>em modo seguro ({@code -dSAFER}), lendo só a pasta temporária desta conversão;</li>
 *   <li>com limite de tempo: passou, o processo é encerrado e o usuário recebe um erro.</li>
 * </ul>
 * Antes, {@link PrintPdf#prepare} tira os links, põe o corte e deixa o preto e os cinzas em escala de cinza (saem só
 * na chapa preta); depois, os cortes (TrimBox e BleedBox) são gravados de novo, porque o Ghostscript não os grava no
 * modo PDF/X quando são a página inteira. O arquivo continua PDF 1.3, sem compactar a estrutura (o X-1a exige).
 */
@Slf4j
@Component
public class PdfX {

    private static final String ICC = "ISOcoated_v2_eci.icc";
    private static final int MAX_LOG = 2000;

    private final String command;
    private final Duration timeout;
    private volatile Boolean available;

    public PdfX(@Value("${booksmith.export.ghostscript:gs}") String command,
                @Value("${booksmith.export.ghostscript-timeout:180s}") Duration timeout) {
        this.command = command;
        this.timeout = timeout;
    }

    /** O Ghostscript está instalado (a resposta fica guardada depois da primeira pergunta). */
    public boolean available() {
        Boolean known = available;
        if (known == null) {
            known = probe();
            available = known;
            if (!known) {
                log.warn("Ghostscript ({}) não encontrado: o PDF para gráfica (CMYK, PDF/X) fica indisponível", command);
            }
        }
        return known;
    }

    private boolean probe() {
        try {
            Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * @param title o título do livro (vai nos dados do PDF)
     * @throws ApiException 503 {@code print_pdf_unavailable} sem o Ghostscript; 500 {@code print_pdf_failed} se a
     *                      conversão falhar ou não sair PDF/X
     */
    public byte[] convert(byte[] pdf, String title) {
        if (!available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "print_pdf_unavailable",
                    "O PDF para gráfica está indisponível agora. Tente mais tarde.");
        }
        byte[] prepared = PrintPdf.prepare(pdf);
        List<PDRectangle[]> boxes = PrintPdf.boxesOf(prepared);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("booksmith-pdfx-");
            Path icc = dir.resolve(ICC);
            try (InputStream in = resource(ICC)) {
                Files.copy(in, icc);
            }
            Path definition = dir.resolve("booksmith-pdfx.ps");
            Files.writeString(definition, definition(icc, title), StandardCharsets.ISO_8859_1);
            Path input = dir.resolve("livro.pdf");
            Files.write(input, prepared);
            Path output = dir.resolve("livro-pdfx.pdf");
            run(dir, icc, definition, input, output);
            return PrintPdf.finish(Files.readAllBytes(output), boxes);
        } catch (IOException e) {
            throw failed("Não foi possível preparar o PDF para gráfica.").causedBy(e);
        } finally {
            delete(dir);
        }
    }

    private void run(Path dir, Path icc, Path definition, Path input, Path output) throws IOException {
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command,
                "-dSAFER", "--permit-file-read=" + slashes(dir) + "/",
                "-dBATCH", "-dNOPAUSE", "-dQUIET",
                "-sDEVICE=pdfwrite", "-dPDFX=1", "-dCompatibilityLevel=1.3",
                "-sColorConversionStrategy=CMYK", "-sOutputICCProfile=" + slashes(icc),
                "-dPDFSETTINGS=/prepress",
                "-sOutputFile=" + slashes(output), slashes(definition), slashes(input))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        // A saída é lida numa thread à parte: se o processo enche o buffer, ele trava esperando quem leia
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(messages);
            } catch (IOException ignored) {
                // o processo foi encerrado
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw failed("O PDF para gráfica demorou demais. Tente de novo; se continuar, fale com o suporte.");
            }
            reader.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw failed("O PDF para gráfica foi interrompido.").causedBy(e);
        }
        String log = messages.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0 || !Files.exists(output)) {
            PdfX.log.error("Ghostscript saiu com {}: {}", process.exitValue(), cut(log));
            throw failed("Não foi possível gerar o PDF para gráfica.");
        }
        // O Ghostscript avisa e grava um PDF comum quando algo impede o padrão: não serve para a gráfica
        if (log.contains("reverting to normal PDF")) {
            PdfX.log.error("Ghostscript não gerou PDF/X: {}", cut(log));
            throw failed("O livro tem algo que o padrão da gráfica (PDF/X) não aceita. Fale com o suporte.");
        }
        PdfX.log.info("PDF/X-1a em {} ms ({} KB)", (System.nanoTime() - start) / 1_000_000, Files.size(output) / 1024);
    }

    /** O arquivo de definição com o caminho do perfil e o título (em UTF-16, como o PDF pede para acentos). */
    static String definition(Path icc, String title) throws IOException {
        String template;
        try (InputStream in = resource("pdfx-1a.ps")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String clean = title == null || title.isBlank() ? "Livro" : title.strip();
        String hex = "<FEFF" + HexFormat.of().withUpperCase().formatHex(clean.getBytes(StandardCharsets.UTF_16BE)) + ">";
        // O caminho vai entre parênteses no PostScript: parênteses e barras invertidas escapados
        String path = slashes(icc).replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        return template.replace("%ICC%", path).replace("%TITLE%", hex);
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = PdfX.class.getResourceAsStream("/print/" + name);
        if (in == null) {
            throw new IOException("Recurso não encontrado: print/" + name);
        }
        return in;
    }

    private static String slashes(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String cut(String text) {
        return text.length() > MAX_LOG ? text.substring(0, MAX_LOG) + "…" : text;
    }

    private static ApiException failed(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "print_pdf_failed", message);
    }

    private static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(files.sorted(Comparator.reverseOrder()).toList());
            for (Path file : all) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            log.warn("Não deu para apagar {}: {}", dir, e.getMessage());
        }
    }
}
