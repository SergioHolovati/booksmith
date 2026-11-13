package com.booksmith.export.internal.drive;

import com.booksmith.common.crypto.SecretBox;
import com.booksmith.common.error.ApiException;
import com.booksmith.export.internal.ExportFormat;
import com.booksmith.export.internal.ExportService;
import com.booksmith.identity.api.UserDirectory;
import com.booksmith.manuscript.api.Manuscripts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriveBackupServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T23:30:00Z");
    private static final String CALLBACK = "http://localhost:8090/oauth2/drive/callback";

    private final Map<String, String> values = new HashMap<>();
    private final DriveConnectionRepository connections = mock(DriveConnectionRepository.class);
    private final DriveFileRepository files = mock(DriveFileRepository.class);
    private final GoogleDriveClient google = mock(GoogleDriveClient.class);
    private final SecretBox box = new SecretBox("", "test-secret-with-at-least-32-characters!!");
    private final ExportService exportService = mock(ExportService.class);
    private final Manuscripts manuscripts = mock(Manuscripts.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final UUID lia = UUID.randomUUID();
    private DriveBackupService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        doAnswer(i -> values.put(i.getArgument(0), i.getArgument(1))).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.getAndDelete(anyString())).thenAnswer(i -> values.remove(i.<String>getArgument(0)));
        when(google.authorizationUrl(anyString(), anyString())).thenAnswer(i -> "https://google?state=" + i.getArgument(1));
        when(connections.save(any())).thenAnswer(i -> i.getArgument(0));
        when(files.save(any())).thenAnswer(i -> i.getArgument(0));
        when(users.displayName(lia)).thenReturn(Optional.of("Lia Moreau"));
        service = new DriveBackupService(connections, files, google, box, redis, exportService, manuscripts, users,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String stateOf(String url) {
        return url.substring(url.indexOf("state=") + 6);
    }

    @Test
    void connectingKeepsTheRefreshTokenEncryptedAndTheStateWorksOnce() {
        String state = stateOf(service.connectUrl(lia, CALLBACK));
        when(google.exchange("codigo", CALLBACK)).thenReturn(new GoogleDriveClient.Tokens("refresh-1", "a", "lia@gmail.com"));

        assertThat(service.finishConnect(state, "codigo", null, CALLBACK)).isEqualTo("connected");
        ArgumentCaptor<DriveConnection> saved = ArgumentCaptor.forClass(DriveConnection.class);
        verify(connections).save(saved.capture());
        assertThat(saved.getValue().getRefreshToken()).isNotEqualTo("refresh-1");
        assertThat(box.open(saved.getValue().getRefreshToken())).isEqualTo("refresh-1");
        assertThat(saved.getValue().getGoogleEmail()).isEqualTo("lia@gmail.com");

        assertThat(service.finishConnect(state, "codigo", null, CALLBACK)).isEqualTo("expired");
        assertThat(service.finishConnect(stateOf(service.connectUrl(lia, CALLBACK)), null, "access_denied", CALLBACK))
                .isEqualTo("denied");
    }

    @Test
    void backupSendsEachBookAsWordAndReusesTheFile() {
        UUID book = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        DriveConnection connection = DriveConnection.builder().userId(lia).refreshToken(box.seal("refresh-1"))
                .folderId("pasta").connectedAt(NOW).build();
        when(connections.findById(lia)).thenReturn(Optional.of(connection));
        when(google.accessToken("refresh-1")).thenReturn("acesso");
        when(google.ensureFolder("acesso", "pasta")).thenReturn("pasta");
        when(manuscripts.bookIds(lia)).thenReturn(List.of(book, empty));
        when(exportService.export(lia, "Lia Moreau", book, ExportFormat.DOCX))
                .thenReturn(new ExportService.ExportFile("A conversão.docx", "application/docx", new byte[] {1}));
        when(exportService.export(lia, "Lia Moreau", empty, ExportFormat.DOCX))
                .thenThrow(ApiException.unprocessable("book_empty", "vazio"));
        when(files.findByUserIdAndBookId(lia, book)).thenReturn(Optional.of(
                DriveFile.builder().userId(lia).bookId(book).fileId("arquivo-1").build()));
        when(google.upload(eq("acesso"), eq("pasta"), eq("arquivo-1"), eq("A conversão.docx"), anyString(), any()))
                .thenReturn("arquivo-1");

        assertThat(service.backup(lia)).isEqualTo(new DriveBackupService.BackupResult(1, 1, NOW));
        assertThat(connection.getLastBackupAt()).isEqualTo(NOW);
        verify(google).upload(eq("acesso"), eq("pasta"), eq("arquivo-1"), eq("A conversão.docx"), anyString(), any());
    }

    @Test
    void accessRemovedInTheGoogleAccountUndoesTheConnection() {
        DriveConnection connection = DriveConnection.builder().userId(lia).refreshToken(box.seal("r")).connectedAt(NOW).build();
        when(connections.findById(lia)).thenReturn(Optional.of(connection));
        when(google.accessToken("r")).thenThrow(new GoogleDriveClient.AccessRevokedException(null));

        assertThatThrownBy(() -> service.backup(lia))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("drive_access_revoked"));
        verify(connections).delete(connection);
        verify(files).deleteAllByUserId(lia);
    }

    @Test
    void disconnectingRevokesAtGoogleEvenIfGoogleFails() {
        DriveConnection connection = DriveConnection.builder().userId(lia).refreshToken(box.seal("r")).connectedAt(NOW).build();
        when(connections.findById(lia)).thenReturn(Optional.of(connection));
        doAnswer(i -> {
            throw new IllegalStateException("fora do ar");
        }).when(google).revoke("r");

        service.disconnect(lia);

        verify(connections).delete(connection);
        assertThat(service.status(UUID.randomUUID()).connected()).isFalse();
        verify(google).revoke("r");
    }
}
