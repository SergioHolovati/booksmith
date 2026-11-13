package com.booksmith.export.internal.drive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * O pouco do Google que o backup usa, direto pela API REST (sem o SDK do Google, que traria dezenas de
 * dependências): autorização com o escopo {@code drive.file} (o Booksmith só vê os arquivos que ele mesmo criou),
 * troca do código por tokens, renovação do acesso, a pasta "Booksmith" e o envio do arquivo de cada livro.
 */
@Component
class GoogleDriveClient {

    static final String AUTHORIZE = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN = "https://oauth2.googleapis.com/token";
    static final String REVOKE = "https://oauth2.googleapis.com/revoke";
    static final String FILES = "https://www.googleapis.com/drive/v3/files";
    static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    /** E-mail (para mostrar qual conta Google guarda o backup) e só os arquivos que o app cria. */
    static final String SCOPES = "openid email https://www.googleapis.com/auth/drive.file";
    static final String FOLDER_NAME = "Booksmith";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    private final RestClient http;
    private final JsonMapper json;
    private final String clientId;
    private final String clientSecret;

    @org.springframework.beans.factory.annotation.Autowired
    GoogleDriveClient(JsonMapper json,
                      @Value("${spring.security.oauth2.client.registration.google.client-id:}") String clientId,
                      @Value("${spring.security.oauth2.client.registration.google.client-secret:}") String clientSecret) {
        this(RestClient.builder(), json, clientId, clientSecret);
    }

    /** Nos testes, o builder vem ligado a um servidor simulado. */
    GoogleDriveClient(RestClient.Builder builder, JsonMapper json, String clientId, String clientSecret) {
        this.http = builder.build();
        this.json = json;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** O Google responde que a autorização não vale mais (a pessoa tirou o acesso na conta Google). */
    static class AccessRevokedException extends RuntimeException {
        AccessRevokedException(Throwable cause) {
            super("O Google não autoriza mais o backup", cause);
        }
    }

    /** {@code email}: a conta Google que autorizou (do id_token). */
    record Tokens(String refreshToken, String accessToken, String email) {
    }

    /** Para onde mandar a pessoa autorizar; {@code prompt=consent} garante o token de renovação. */
    String authorizationUrl(String redirectUri, String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPES)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("include_granted_scopes", "true")
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    Tokens exchange(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        JsonNode body = postForm(form);
        return new Tokens(text(body, "refresh_token"), text(body, "access_token"), emailOf(text(body, "id_token")));
    }

    /** Um token de acesso novo (vale 1 hora) a partir do de renovação. */
    String accessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "refresh_token");
        try {
            return text(postForm(form), "access_token");
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 401) {
                throw new AccessRevokedException(e);
            }
            throw e;
        }
    }

    /** A pasta "Booksmith" do backup: a guardada, se ainda existe e não está na lixeira; senão, uma nova. */
    String ensureFolder(String accessToken, String folderId) {
        if (folderId != null) {
            try {
                JsonNode folder = json.readTree(http.get()
                        .uri(FILES + "/{id}?fields=id,trashed", folderId)
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve().body(String.class));
                if (!folder.path("trashed").asBoolean(false)) {
                    return folderId;
                }
            } catch (HttpClientErrorException.NotFound e) {
                // A pessoa apagou a pasta: cria outra
            }
        }
        String body = json.writeValueAsString(Map.of("name", FOLDER_NAME, "mimeType", FOLDER_MIME));
        return text(json.readTree(http.post()
                .uri(FILES + "?fields=id")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(String.class)), "id");
    }

    /**
     * Envia o arquivo: atualiza o mesmo arquivo do livro (o Drive guarda as versões anteriores) ou, sem ele (ou se
     * a pessoa o apagou), cria um novo na pasta. Devolve o id do arquivo no Drive.
     */
    String upload(String accessToken, String folderId, String fileId, String name, String contentType, byte[] bytes) {
        if (fileId != null) {
            try {
                return send(accessToken, "PATCH", UPLOAD + "/" + fileId + "?uploadType=multipart&fields=id",
                        Map.of("name", name), contentType, bytes);
            } catch (HttpClientErrorException.NotFound e) {
                // Apagado no Drive: cria de novo
            }
        }
        return send(accessToken, "POST", UPLOAD + "?uploadType=multipart&fields=id",
                Map.of("name", name, "parents", java.util.List.of(folderId)), contentType, bytes);
    }

    /** Tira a autorização no Google (desligar o backup). Falha aqui não impede desligar no Booksmith. */
    void revoke(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        http.post().uri(REVOKE).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toBodilessEntity();
    }

    /** Corpo multipart/related: os metadados em JSON e o arquivo. */
    private String send(String accessToken, String method, String url, Map<String, Object> metadata, String contentType,
                        byte[] bytes) {
        String boundary = "booksmith-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + json.writeValueAsString(metadata) + "\r\n--" + boundary + "\r\nContent-Type: " + contentType
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
        String response = http.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(url)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.parseMediaType("multipart/related; boundary=" + boundary))
                .body(body.toByteArray())
                .retrieve().body(String.class);
        return text(json.readTree(response), "id");
    }

    private JsonNode postForm(MultiValueMap<String, String> form) {
        return json.readTree(http.post().uri(TOKEN).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(String.class));
    }

    /** O e-mail do id_token, que veio direto do Google por TLS na troca do código (não precisa conferir assinatura). */
    private String emailOf(String idToken) {
        if (idToken == null || idToken.split("\\.").length < 2) {
            return null;
        }
        byte[] payload = Base64.getUrlDecoder().decode(idToken.split("\\.")[1]);
        return text(json.readTree(new String(payload, StandardCharsets.UTF_8)), "email");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
