package com.booksmith.export.internal.drive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleDriveClientTest {

    private MockRestServiceServer server;
    private GoogleDriveClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GoogleDriveClient(builder, JsonMapper.builder().build(), "cliente.apps.googleusercontent.com", "segredo");
    }

    @Test
    void asksOnlyForTheFilesTheAppCreatesAndForARefreshToken() {
        String url = client.authorizationUrl("http://localhost:8090/oauth2/drive/callback", "estado");

        assertThat(url).startsWith(GoogleDriveClient.AUTHORIZE)
                .contains("scope=openid%20email%20https://www.googleapis.com/auth/drive.file")
                .contains("access_type=offline").contains("prompt=consent").contains("state=estado")
                .contains("redirect_uri=http://localhost:8090/oauth2/drive/callback")
                .doesNotContain("auth/drive%20").doesNotContain("drive.readonly");
    }

    @Test
    void exchangesTheCodeAndReadsTheEmailFromTheIdToken() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"email\":\"lia@gmail.com\"}".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(GoogleDriveClient.TOKEN)).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(containsString("grant_type=authorization_code"), containsString("code=c0d1g0"))))
                .andRespond(withSuccess("{\"refresh_token\":\"r1\",\"access_token\":\"a1\",\"id_token\":\"x." + payload + ".y\"}",
                        MediaType.APPLICATION_JSON));

        GoogleDriveClient.Tokens tokens = client.exchange("c0d1g0", "http://localhost:8090/oauth2/drive/callback");

        assertThat(tokens).isEqualTo(new GoogleDriveClient.Tokens("r1", "a1", "lia@gmail.com"));
    }

    @Test
    void revokedAccessIsRecognized() {
        server.expect(requestTo(GoogleDriveClient.TOKEN))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"invalid_grant\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.accessToken("r1")).isInstanceOf(GoogleDriveClient.AccessRevokedException.class);
    }

    @Test
    void deletedFolderIsCreatedAgain() {
        server.expect(requestTo(GoogleDriveClient.FILES + "/pasta-velha?fields=id,trashed"))
                .andRespond(withSuccess("{\"id\":\"pasta-velha\",\"trashed\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(GoogleDriveClient.FILES + "?fields=id")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("application/vnd.google-apps.folder")))
                .andRespond(withSuccess("{\"id\":\"pasta-nova\"}", MediaType.APPLICATION_JSON));

        assertThat(client.ensureFolder("a1", "pasta-velha")).isEqualTo("pasta-nova");
        server.verify();
    }

    @Test
    void updatesTheSameFileAndRecreatesItWhenDeletedInTheDrive() {
        server.expect(requestTo(GoogleDriveClient.UPLOAD + "/arquivo-1?uploadType=multipart&fields=id"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer a1"))
                .andExpect(content().string(allOf(containsString("\"name\":\"A conversão.docx\""), containsString("PK-docx"))))
                .andRespond(withSuccess("{\"id\":\"arquivo-1\"}", MediaType.APPLICATION_JSON));
        assertThat(client.upload("a1", "pasta", "arquivo-1", "A conversão.docx", "application/octet-stream",
                "PK-docx".getBytes(StandardCharsets.UTF_8))).isEqualTo("arquivo-1");

        server.reset();
        server.expect(requestTo(GoogleDriveClient.UPLOAD + "/apagado?uploadType=multipart&fields=id"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(GoogleDriveClient.UPLOAD + "?uploadType=multipart&fields=id")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"parents\":[\"pasta\"]")))
                .andRespond(withSuccess("{\"id\":\"arquivo-2\"}", MediaType.APPLICATION_JSON));
        assertThat(client.upload("a1", "pasta", "apagado", "A conversão.docx", "application/octet-stream", new byte[] {1}))
                .isEqualTo("arquivo-2");
        server.verify();
    }
}
