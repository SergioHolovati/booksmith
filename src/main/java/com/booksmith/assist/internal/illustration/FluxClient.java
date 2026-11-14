package com.booksmith.assist.internal.illustration;

import com.booksmith.common.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FLUX.2 pela API da Black Forest Labs (api.bfl.ai): o pedido é assíncrono. O POST devolve um {@code polling_url};
 * consulta-se até {@code Ready} e baixa-se a imagem de {@code result.sample} (URL assinada, vale 10 minutos, só de
 * {@code *.bfl.ai}). Imagens recusadas pelo filtro ({@code Request Moderated} / {@code Content Moderated}) não são
 * cobradas pelo fornecedor.
 */
@Slf4j
class FluxClient implements ImageModel {

    static final int MAX_REFERENCES = 8;
    private static final Duration POLL_EVERY = Duration.ofMillis(1500);

    private final RestClient http;
    private final IllustrationProperties.Flux flux;
    private final Duration timeout;
    private final Sleeper sleeper;

    FluxClient(IllustrationProperties properties) {
        this(RestClient.builder().requestFactory(timeouts()), properties, Thread::sleep);
    }

    /** Para os testes: o transporte vem no {@code builder}. */
    FluxClient(RestClient.Builder builder, IllustrationProperties properties, Sleeper sleeper) {
        this.http = builder.build();
        this.flux = properties.flux();
        this.timeout = properties.timeout();
        this.sleeper = sleeper;
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return factory;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Override
    public String provider() {
        return "flux";
    }

    @Override
    public String model() {
        return flux.model();
    }

    @Override
    public Generated generate(String prompt, int width, int height, List<String> references) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        body.put("width", width);
        body.put("height", height);
        body.put("output_format", "jpeg");
        body.put("safety_tolerance", flux.safetyTolerance());
        for (int i = 0; i < Math.min(references.size(), MAX_REFERENCES); i++) {
            body.put(i == 0 ? "input_image" : "input_image_" + (i + 1), references.get(i));
        }
        JsonNode started;
        try {
            started = http.post()
                    .uri(flux.baseUrl() + "/v1/" + flux.model())
                    .header("x-key", flux.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("FLUX recusou o pedido: {}", e.getMessage());
            throw failed().causedBy(e);
        }
        if (started == null || !started.hasNonNull("polling_url")) {
            throw failed();
        }
        String pollingUrl = started.get("polling_url").stringValue();
        requireBflHost(pollingUrl);
        Double cost = started.hasNonNull("cost") ? started.get("cost").asDouble() : null;
        return poll(pollingUrl, cost);
    }

    private Generated poll(String pollingUrl, Double startCost) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode status;
            try {
                sleeper.sleep(POLL_EVERY.toMillis());
                status = http.get().uri(URI.create(pollingUrl)).header("x-key", flux.apiKey())
                        .accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failed().causedBy(e);
            } catch (RestClientException e) {
                log.warn("FLUX: falha ao consultar o pedido: {}", e.getMessage());
                continue;
            }
            String state = status == null || !status.hasNonNull("status") ? "" : status.get("status").stringValue();
            switch (state) {
                case "Ready" -> {
                    Double cost = status.hasNonNull("cost") ? Double.valueOf(status.get("cost").asDouble()) : startCost;
                    return new Generated(download(status.path("result").path("sample").stringValue("")),
                            "image/jpeg", cost);
                }
                case "Request Moderated", "Content Moderated" -> throw moderated();
                case "Error", "Failed", "Task not found", "No Card Generated" -> throw failed();
                default -> {
                    // Pending, Reasoning, Generating: continua esperando
                }
            }
        }
        throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "illustration_timeout",
                "A ilustração demorou demais para ficar pronta. Tente de novo em instantes; nada foi descontado.");
    }

    private byte[] download(String sample) {
        requireBflHost(sample);
        try {
            byte[] bytes = http.get().uri(URI.create(sample)).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw failed();
            }
            return bytes;
        } catch (RestClientException e) {
            throw failed().causedBy(e);
        }
    }

    /** Só baixa de {@code *.bfl.ai}: a URL vem da resposta, e o servidor nunca deve buscar um endereço qualquer. */
    static void requireBflHost(String url) {
        String host;
        try {
            URI uri = URI.create(url);
            host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(uri.getScheme())) {
                host = "";
            }
        } catch (IllegalArgumentException e) {
            host = "";
        }
        if (!host.equals("bfl.ai") && !host.endsWith(".bfl.ai")) {
            throw failed();
        }
    }

    static ApiException moderated() {
        return ApiException.unprocessable("illustration_moderated",
                "O filtro de conteúdo do gerador recusou este pedido. Mude a descrição (cenas de violência explícita, "
                        + "nudez e personagens de outras obras costumam ser recusados). Nada foi descontado.");
    }

    static ApiException failed() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "illustration_failed",
                "O gerador de imagens não respondeu como esperado. Tente de novo em instantes; nada foi descontado.");
    }
}
