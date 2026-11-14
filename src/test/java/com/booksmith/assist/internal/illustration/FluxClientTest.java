package com.booksmith.assist.internal.illustration;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FluxClientTest {

    private static final String POLL = "https://api.us1.bfl.ai/v1/get_result?id=abc";
    private static final String SAMPLE = "https://delivery.us1.bfl.ai/results/abc/sample.jpeg";

    private MockRestServiceServer server;
    private FluxClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FluxClient(builder, new IllustrationProperties("flux", 3, 2, Duration.ofSeconds(30),
                new IllustrationProperties.Flux("chave", "flux-2-pro", "https://api.bfl.ai", 2)), millis -> { });
    }

    private void started() {
        server.expect(requestTo("https://api.bfl.ai/v1/flux-2-pro"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-key", "chave"))
                .andExpect(jsonPath("$.prompt").value("um farol"))
                .andExpect(jsonPath("$.width").value(1024))
                .andExpect(jsonPath("$.safety_tolerance").value(2))
                .andExpect(jsonPath("$.input_image").value("https://booksmith.com.br/media/gallery/lia.jpg"))
                .andExpect(jsonPath("$.input_image_2").value("aGVsbG8="))
                .andRespond(withSuccess("{\"id\":\"abc\",\"polling_url\":\"" + POLL + "\",\"cost\":4.5}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void postsThenPollsUntilReadyAndDownloadsTheSample() {
        started();
        server.expect(requestTo(POLL)).andRespond(withSuccess("{\"id\":\"abc\",\"status\":\"Pending\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(POLL)).andRespond(withSuccess(
                "{\"id\":\"abc\",\"status\":\"Ready\",\"cost\":4.5,\"result\":{\"sample\":\"" + SAMPLE + "\"}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(SAMPLE)).andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.IMAGE_JPEG));

        var generated = client.generate("um farol", 1024, 1024,
                List.of("https://booksmith.com.br/media/gallery/lia.jpg", "aGVsbG8="));

        assertThat(generated.bytes()).containsExactly(1, 2, 3);
        assertThat(generated.costCredits()).isEqualTo(4.5);
        server.verify();
    }

    @Test
    void aModeratedRequestBecomesAClearError() {
        started();
        server.expect(requestTo(POLL)).andRespond(withSuccess("{\"id\":\"abc\",\"status\":\"Content Moderated\"}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("um farol", 1024, 1024,
                List.of("https://booksmith.com.br/media/gallery/lia.jpg", "aGVsbG8=")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_moderated"));
    }

    @Test
    void neverDownloadsFromAnAddressOutsideBfl() {
        assertThatThrownBy(() -> FluxClient.requireBflHost("https://evil.example.com/x.jpg")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FluxClient.requireBflHost("http://delivery.bfl.ai/x.jpg")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FluxClient.requireBflHost("https://bfl.ai.evil.com/x.jpg")).isInstanceOf(ApiException.class);
        FluxClient.requireBflHost(SAMPLE);
    }
}
