package com.booksmith.media.internal;

import com.booksmith.common.alerts.SecurityIncident;
import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ClamAvScannerTest {

    /**
     * O arquivo de teste EICAR (inofensivo; todo antivírus o reconhece), montado em pedaços: escrito inteiro no código,
     * o próprio antivírus do computador apagaria este arquivo de teste.
     */
    static byte[] eicar() {
        String a = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$";
        String b = "EICAR-STANDARD-ANTIVIRUS-";
        String c = "TEST-FILE!$H+H*";
        return (a + b + c).getBytes(StandardCharsets.US_ASCII);
    }

    private final List<Object> events = new ArrayList<>();
    private final ApplicationEventPublisher publisher = events::add;

    @Test
    void speaksTheClamdProtocolAndRefusesWhatItFinds() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> answer = new AtomicReference<>("stream: OK");
        try (ServerSocket server = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try (Socket client = server.accept()) {
                        DataInputStream in = new DataInputStream(client.getInputStream());
                        byte[] command = new byte[10];
                        in.readFully(command);
                        assertThat(new String(command, StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                        ByteArrayOutputStream file = new ByteArrayOutputStream();
                        int size;
                        while ((size = in.readInt()) > 0) {
                            file.write(in.readNBytes(size));
                        }
                        received.set(file.toByteArray());
                        client.getOutputStream().write((answer.get() + "\0").getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception ignored) {
                        // servidor fechado no fim do teste
                    }
                }
            });
            var scanner = new ClamAvScanner("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(5), publisher);
            byte[] big = new byte[200_000];
            big[199_999] = 7;
            scanner.requireClean(big);
            assertThat(received.get()).isEqualTo(big);

            answer.set("stream: Eicar-Test-Signature FOUND");
            assertThatThrownBy(() -> scanner.requireClean(new byte[] {1, 2, 3}))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Eicar-Test-Signature");
            assertThat(events).hasSize(1);
            assertThat(((SecurityIncident) events.getFirst()).kind()).isEqualTo(SecurityIncident.Kind.FILE_INFECTED);
        }
    }

    @Test
    void withoutTheAntivirusTheFilePassesAndItIsLogged() {
        // Desligado (desenvolvimento) e fora do ar (porta sem ninguém): não trava o envio
        new ClamAvScanner("", 3310, Duration.ofSeconds(2), publisher).requireClean(eicar());
        new ClamAvScanner("127.0.0.1", 1, Duration.ofSeconds(2), publisher).requireClean(eicar());
        assertThat(events).isEmpty();
    }

    @Test
    void theRealClamAvFindsTheTestFile() {
        assumeTrue(listening(3310), "ClamAV local não está no ar (docker compose --profile antivirus up -d clamav)");
        var scanner = new ClamAvScanner("127.0.0.1", 3310, Duration.ofSeconds(60), publisher);
        scanner.requireClean("Era uma vez um farol.".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> scanner.requireClean(eicar()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Eicar");
    }

    private static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            // O clamd só responde depois de carregar as assinaturas
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.setSoTimeout(3000);
            return new String(socket.getInputStream().readNBytes(4), StandardCharsets.US_ASCII).equals("PONG");
        } catch (Exception e) {
            return false;
        }
    }
}
