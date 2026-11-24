package com.booksmith.media.internal;

import com.booksmith.common.alerts.SecurityIncident;
import com.booksmith.common.error.ApiException;
import com.booksmith.media.api.FileScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * O ClamAV (contêiner "clamav", com as assinaturas atualizadas pelo freshclam) pelo protocolo do clamd:
 * {@code zINSTREAM}, o arquivo em pedaços (tamanho de 4 bytes + dados) e um pedaço vazio no fim; a resposta é
 * {@code stream: OK} ou {@code stream: <nome> FOUND}.
 * <p>
 * Sem endereço configurado (desenvolvimento), não confere. Antivírus fora do ar ou arquivo maior que o limite dele:
 * o arquivo passa e fica no log. A decisão é de propósito: nenhum arquivo enviado é executado nem devolvido a outra
 * pessoa como veio, e o beta não para porque o antivírus reiniciou.
 */
@Slf4j
@Component
class ClamAvScanner implements FileScanner {

    private static final int CHUNK = 64 * 1024;
    private static final int MAX_REPLY = 1024;

    private final String host;
    private final int port;
    private final Duration timeout;
    private final ApplicationEventPublisher events;

    ClamAvScanner(@Value("${booksmith.antivirus.host:}") String host,
                  @Value("${booksmith.antivirus.port:3310}") int port,
                  @Value("${booksmith.antivirus.timeout:120s}") Duration timeout,
                  ApplicationEventPublisher events) {
        this.host = host == null || host.isBlank() ? null : host;
        this.port = port;
        this.timeout = timeout;
        this.events = events;
        if (this.host == null) {
            log.warn("Antivírus DESLIGADO (booksmith.antivirus.host vazio). Só para desenvolvimento.");
        }
    }

    @Override
    public void requireClean(byte[] content) {
        try {
            requireClean(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void requireClean(InputStream content) throws IOException {
        if (host == null) {
            return;
        }
        String reply;
        long start = System.nanoTime();
        try {
            reply = scan(content);
        } catch (IOException e) {
            log.warn("Antivírus indisponível ({}:{}): o arquivo passou sem conferência. {}", host, port, e.getMessage());
            return;
        }
        if (reply.endsWith("FOUND")) {
            String found = reply.replaceFirst("^stream: ", "").replaceFirst(" FOUND$", "");
            events.publishEvent(new SecurityIncident(SecurityIncident.Kind.FILE_INFECTED, found));
            log.warn("Antivírus: arquivo recusado ({})", found);
            throw ApiException.unprocessable("file_infected",
                    "O arquivo foi recusado: o antivírus encontrou um problema nele (" + found + ").");
        }
        if (!reply.endsWith("OK")) {
            log.warn("Antivírus não conferiu o arquivo: {}", reply);
            return;
        }
        log.debug("Antivírus: ok em {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    // O fluxo de saída é do socket: fechá-lo antes de ler a resposta encerraria a conexão; o try fecha o socket e,
    // com ele, os fluxos
    @SuppressWarnings("PMD.CloseResource")
    private String scan(InputStream content) throws IOException {
        // Sem TLS: o clamd não tem. A conversa fica na rede interna do Docker ("scan"), na mesma máquina, só entre a API
        // e o contêiner do antivírus
        try (Socket socket = new Socket()) { // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setSoTimeout((int) timeout.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[CHUNK];
            int read;
            while ((read = content.read(buffer)) > 0) {
                out.write(size(read));
                out.write(buffer, 0, read);
            }
            out.write(size(0));
            out.flush();
            return reply(socket.getInputStream());
        }
    }

    /** O tamanho do pedaço: 4 bytes, o mais significativo primeiro. */
    private static byte[] size(int value) {
        return java.nio.ByteBuffer.allocate(4).putInt(value).array();
    }

    /** A resposta termina em {@code \0}. */
    static String reply(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0 && bytes.size() < MAX_REPLY) {
            bytes.write(b);
        }
        return bytes.toString(StandardCharsets.US_ASCII).strip();
    }
}
