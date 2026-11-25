package com.booksmith.shelf.internal;

import com.booksmith.identity.api.UserDirectory;
import com.booksmith.notification.api.EmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShelfNotifierTest {

    private final UserDirectory users = mock(UserDirectory.class);
    private final EmailSender email = mock(EmailSender.class);
    private final ShelfNotifier notifier = new ShelfNotifier(mock(JdbcClient.class), users, email, Clock.systemUTC(),
            "https://booksmith.com.br/", Runnable::run);
    private final ShelfNotifier.NewChapters added = new ShelfNotifier.NewChapters(UUID.randomUUID(), "o-farol-abc123",
            "O Farol", "Pena", 4, "A tempestade", 2);

    @Test
    void theMessageLinksToTheFirstNewChapterAndSaysHowToStop() {
        String message = notifier.message(added);
        assertThat(message)
                .contains("Pena publicou 2 capítulos novos em \"O Farol\": A tempestade.")
                .contains("https://booksmith.com.br/estante/obra/o-farol-abc123/4")
                .contains("desligue em https://booksmith.com.br/estante/biblioteca");
        assertThat(notifier.message(new ShelfNotifier.NewChapters(added.workId(), "s", "T", "A", 1, "C", 1)))
                .contains("publicou um capítulo novo");
    }

    @Test
    void oneFailedEmailDoesNotStopTheOthers() {
        UUID lia = UUID.randomUUID();
        UUID semEmail = UUID.randomUUID();
        UUID ana = UUID.randomUUID();
        when(users.email(lia)).thenReturn(Optional.of("lia@example.com"));
        when(users.email(semEmail)).thenReturn(Optional.empty());
        when(users.email(ana)).thenReturn(Optional.of("ana@example.com"));
        doThrow(new IllegalStateException("caixa cheia")).when(email).sendNotice(eq("lia@example.com"), anyString(), anyString());

        notifier.send(List.of(lia, semEmail, ana), added);

        verify(email, times(2)).sendNotice(anyString(), eq("Capítulo novo: O Farol"), anyString());
        verify(email).sendNotice(eq("ana@example.com"), anyString(), anyString());
    }
}
