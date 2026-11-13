package com.booksmith.export.internal.drive;

import com.booksmith.common.crypto.SecretBox;
import com.booksmith.common.error.ApiException;
import com.booksmith.export.internal.ExportFormat;
import com.booksmith.export.internal.ExportService;
import com.booksmith.identity.api.UserDirectory;
import com.booksmith.manuscript.api.Manuscripts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Backup dos livros no Google Drive da pessoa (pedido do autor): cada livro vira um Word (.docx, que volta pela
 * importação) na pasta "Booksmith" do Drive. "Fazer backup agora" no perfil e, para quem ligou, um backup toda
 * madrugada. O Drive guarda as versões anteriores de cada arquivo.
 *
 * <p>Ligar: {@link #connectUrl} manda ao Google com um {@code state} de uso único (Redis, 10 minutos);
 * {@link #finishConnect} troca o código pelos tokens e guarda o de renovação criptografado.
 */
@Slf4j
@Service
public class DriveBackupService {

    static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final String STATE_PREFIX = "drive:state:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DriveConnectionRepository connections;
    private final DriveFileRepository files;
    private final GoogleDriveClient google;
    private final SecretBox secretBox;
    private final StringRedisTemplate redis;
    private final ExportService exportService;
    private final Manuscripts manuscripts;
    private final UserDirectory users;
    private final Clock clock;

    DriveBackupService(DriveConnectionRepository connections, DriveFileRepository files, GoogleDriveClient google,
                       SecretBox secretBox, StringRedisTemplate redis, ExportService exportService,
                       Manuscripts manuscripts, UserDirectory users, Clock clock) {
        this.connections = connections;
        this.files = files;
        this.google = google;
        this.secretBox = secretBox;
        this.redis = redis;
        this.exportService = exportService;
        this.manuscripts = manuscripts;
        this.users = users;
        this.clock = clock;
    }

    /** {@code books}: quantos livros já estão no Drive. */
    public record Status(boolean connected, String googleEmail, Instant lastBackupAt, String lastError, long books) {
    }

    public record BackupResult(int books, int skipped, Instant at) {
    }

    @Transactional(readOnly = true)
    public Status status(UUID userId) {
        return connections.findById(userId)
                .map(c -> new Status(true, c.getGoogleEmail(), c.getLastBackupAt(), c.getLastError(), files.countByUserId(userId)))
                .orElse(new Status(false, null, null, null, 0));
    }

    /** Para onde mandar a pessoa autorizar o Drive. */
    public String connectUrl(UUID userId, String redirectUri) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(STATE_PREFIX + sha256(state), userId.toString(), STATE_TTL);
        return google.authorizationUrl(redirectUri, state);
    }

    /**
     * A volta do Google. Devolve o que o perfil mostra: {@code connected}, {@code denied} (a pessoa não autorizou),
     * {@code expired} (state vencido ou já usado) ou {@code error}.
     */
    @Transactional
    public String finishConnect(String state, String code, String error, String redirectUri) {
        String userId = state == null ? null : redis.opsForValue().getAndDelete(STATE_PREFIX + sha256(state));
        if (userId == null) {
            return "expired";
        }
        if (error != null || code == null) {
            return "denied";
        }
        try {
            GoogleDriveClient.Tokens tokens = google.exchange(code, redirectUri);
            if (tokens.refreshToken() == null) {
                return "error";
            }
            UUID owner = UUID.fromString(userId);
            DriveConnection connection = connections.findById(owner).orElseGet(() -> DriveConnection.builder().userId(owner).build());
            connection.setRefreshToken(secretBox.seal(tokens.refreshToken()));
            connection.setGoogleEmail(tokens.email());
            connection.setConnectedAt(Instant.now(clock));
            connection.setLastError(null);
            connections.save(connection);
            log.info("Backup no Google Drive ligado para userId: {}", owner);
            return "connected";
        } catch (RuntimeException e) {
            log.warn("Falha ao ligar o Google Drive: {}", e.getMessage());
            return "error";
        }
    }

    /**
     * Envia todos os livros (com capítulos) para o Drive.
     *
     * @throws ApiException 409 {@code drive_not_connected}; 409 {@code drive_access_revoked} se a pessoa tirou o
     *                      acesso no Google (a ligação é desfeita)
     */
    @Transactional(noRollbackFor = ApiException.class)
    public BackupResult backup(UUID userId) {
        DriveConnection connection = connections.findById(userId)
                .orElseThrow(() -> ApiException.conflict("drive_not_connected", "Ligue o Google Drive primeiro.", Map.of()));
        String access;
        try {
            access = google.accessToken(secretBox.open(connection.getRefreshToken()));
        } catch (GoogleDriveClient.AccessRevokedException e) {
            files.deleteAllByUserId(userId);
            connections.delete(connection);
            log.info("Acesso ao Google Drive retirado pela pessoa; ligação desfeita para userId: {}", userId);
            throw ApiException.conflict("drive_access_revoked",
                    "O Google não autoriza mais o backup (o acesso foi retirado na conta Google). Ligue de novo.", Map.of())
                    .causedBy(e);
        }
        try {
            String folder = google.ensureFolder(access, connection.getFolderId());
            connection.setFolderId(folder);
            String author = users.displayName(userId).orElse("Autor");
            int sent = 0;
            int skipped = 0;
            for (UUID bookId : manuscripts.bookIds(userId)) {
                ExportService.ExportFile file;
                try {
                    file = exportService.export(userId, author, bookId, ExportFormat.DOCX);
                } catch (ApiException e) {
                    // Livro sem capítulos: nada a guardar ainda
                    skipped++;
                    continue;
                }
                DriveFile saved = files.findByUserIdAndBookId(userId, bookId)
                        .orElseGet(() -> DriveFile.builder().userId(userId).bookId(bookId).build());
                saved.setFileId(google.upload(access, folder, saved.getFileId(), file.filename(), file.contentType(), file.bytes()));
                saved.setBackedUpAt(Instant.now(clock));
                files.save(saved);
                sent++;
            }
            connection.setLastBackupAt(Instant.now(clock));
            connection.setLastError(null);
            // Salva explícito: o backup noturno chama este método por dentro, sem a transação do proxy
            connections.save(connection);
            log.info("Backup no Google Drive de userId: {}: {} livros", userId, sent);
            return new BackupResult(sent, skipped, connection.getLastBackupAt());
        } catch (RuntimeException e) {
            connection.setLastError("O último backup falhou. Tente de novo em alguns minutos.");
            connections.save(connection);
            log.warn("Backup no Google Drive falhou para userId: {}: {}", userId, e.getMessage());
            throw ApiException.unprocessable("drive_backup_failed",
                    "O Google Drive não respondeu. Tente de novo em alguns minutos.").causedBy(e);
        }
    }

    /** Desliga o backup: tira a autorização no Google e esquece a ligação. Os arquivos ficam no Drive da pessoa. */
    @Transactional
    public void disconnect(UUID userId) {
        connections.findById(userId).ifPresent(connection -> {
            try {
                google.revoke(secretBox.open(connection.getRefreshToken()));
            } catch (RuntimeException e) {
                log.warn("Não foi possível revogar o acesso ao Drive no Google: {}", e.getMessage());
            }
            files.deleteAllByUserId(userId);
            connections.delete(connection);
            log.info("Backup no Google Drive desligado para userId: {}", userId);
        });
    }

    /** Toda madrugada, um backup de quem ligou o Drive. Falha de uma pessoa não para as outras. */
    @Scheduled(cron = "${booksmith.drive.backup-cron:0 0 4 * * *}", zone = "America/Sao_Paulo")
    public void nightly() {
        for (DriveConnection connection : connections.findAll()) {
            try {
                backup(connection.getUserId());
            } catch (RuntimeException e) {
                log.warn("Backup noturno no Drive não saiu para userId: {}: {}", connection.getUserId(), e.getMessage());
            }
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
