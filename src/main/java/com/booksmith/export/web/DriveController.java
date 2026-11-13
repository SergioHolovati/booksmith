package com.booksmith.export.web;

import com.booksmith.export.internal.drive.DriveBackupService;
import com.booksmith.identity.api.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;

/**
 * Backup no Google Drive (Perfil → Backup no Google Drive). A volta do Google cai em
 * {@code /oauth2/drive/callback} (fora de /api: o navegador chega lá vindo do Google, sem o token do app), que
 * precisa estar entre os URIs de redirecionamento autorizados da credencial no Google Cloud.
 */
@RestController
@RequiredArgsConstructor
class DriveController {

    static final String CALLBACK = "/oauth2/drive/callback";

    private final DriveBackupService drive;
    private final CurrentUser currentUser;

    @Value("${booksmith.security.frontend-url}")
    private String frontendUrl;

    @GetMapping("/api/v1/me/drive")
    DriveBackupService.Status status() {
        return drive.status(currentUser.id());
    }

    /** Para onde o app manda a pessoa autorizar o Drive. */
    @PostMapping("/api/v1/me/drive/connect")
    ConnectUrl connect() {
        return new ConnectUrl(drive.connectUrl(currentUser.id(), callbackUri()));
    }

    @PostMapping("/api/v1/me/drive/backup")
    DriveBackupService.BackupResult backup() {
        return drive.backup(currentUser.id());
    }

    @DeleteMapping("/api/v1/me/drive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect() {
        drive.disconnect(currentUser.id());
    }

    /** A volta do Google: liga o Drive e manda de volta ao perfil com o resultado. */
    @GetMapping(CALLBACK)
    void callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
                  @RequestParam(required = false) String error, HttpServletResponse response) throws IOException {
        String result = drive.finishConnect(state, code, error, callbackUri());
        response.sendRedirect(frontendUrl + profilePath(result));
    }

    /** Só destinos fixos dentro do app (nada que venha do pedido vai para a URL de volta). */
    static String profilePath(String result) {
        return switch (result) {
            case "connected" -> "/profile?drive=connected";
            case "denied" -> "/profile?drive=denied";
            case "expired" -> "/profile?drive=expired";
            default -> "/profile?drive=error";
        };
    }

    private static String callbackUri() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(CALLBACK).toUriString();
    }

    record ConnectUrl(String url) {
    }
}
