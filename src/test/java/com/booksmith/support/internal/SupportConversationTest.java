package com.booksmith.support.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.AuthenticatedUser;
import com.booksmith.identity.api.UserDirectory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportConversationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);

    private final SupportRequestRepository requests = mock(SupportRequestRepository.class);
    private final SupportMessageRepository messages = mock(SupportMessageRepository.class);
    private final SupportMailer mailer = mock(SupportMailer.class);
    private final SupportService service = new SupportService(requests, messages, mailer, mock(HelpdeskGateway.class),
            mock(UserDirectory.class), JsonMapper.builder().build(), CLOCK);
    private final SupportDesk desk = new SupportDesk(requests, messages, mailer, CLOCK);

    private final AuthenticatedUser lia = new AuthenticatedUser(UUID.randomUUID(), "lia", "Lia", "s1");
    private final UUID admin = UUID.randomUUID();
    private final List<SupportMessage> saved = new ArrayList<>();
    private final SupportRequest request = SupportRequest.builder()
            .id(UUID.fromString("0196f000-0000-7000-8000-00003f9a2c71")).userId(lia.id()).email("lia@exemplo.com")
            .category("bug").message("O botão de salvar some no celular.").priority("normal").status("open").build();

    SupportConversationTest() {
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        when(messages.save(any())).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(messages.findAllByRequestIdOrderByCreatedAtAsc(request.getId())).thenAnswer(i -> List.copyOf(saved));
        when(messages.findAllByRequestIdAndInternalFalseOrderByCreatedAtAsc(request.getId()))
                .thenAnswer(i -> saved.stream().filter(m -> !m.isInternal()).toList());
    }

    @Test
    void theTeamAnswersAndThePersonIsToldByEmail() {
        var conversation = desk.reply(admin, request.getId(), "  Já corrigimos, atualize o app.  ", false);

        assertThat(request.getStatus()).isEqualTo(SupportService.WAITING_USER);
        assertThat(conversation.messages()).singleElement()
                .satisfies(m -> {
                    assertThat(m.getAuthor()).isEqualTo(SupportMessage.STAFF);
                    assertThat(m.getBody()).isEqualTo("Já corrigimos, atualize o app.");
                });
        verify(mailer).staffReplied(request, "Já corrigimos, atualize o app.");
    }

    @Test
    void anInternalNoteNeverReachesThePerson() {
        desk.reply(admin, request.getId(), "Parece o bug do Safari antigo.", true);

        assertThat(request.getStatus()).isEqualTo("open");
        verify(mailer, never()).staffReplied(any(), any());
        assertThat(service.conversation(lia.id(), request.getId()).messages()).isEmpty();
        assertThat(desk.conversation(request.getId()).messages()).hasSize(1);
    }

    @Test
    void thePersonRepliesAndTheRequestGoesBackToTheTeam() {
        desk.status(admin, request.getId(), SupportService.RESOLVED);

        service.reply(lia, request.getId(), "Voltou a acontecer hoje.");

        assertThat(request.getStatus()).isEqualTo(SupportService.OPEN);
        verify(mailer).userReplied(request);
    }

    @Test
    void someoneElsesRequestDoesNotExist() {
        var other = new AuthenticatedUser(UUID.randomUUID(), "outro", "Outro", "s2");
        assertThatThrownBy(() -> service.conversation(other.id(), request.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.reply(other, request.getId(), "oi"))
                .isInstanceOf(ApiException.class);
        verify(messages, never()).save(any());
    }

    @Test
    void emptyMessagesAndUnknownStatusesAreRefused() {
        assertThatThrownBy(() -> desk.reply(admin, request.getId(), "   ", false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> desk.status(admin, request.getId(), "lixo")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> desk.inbox("lixo")).isInstanceOf(ApiException.class);
    }

    @Test
    void waitingForTheTeamPutsHighPriorityFirst() {
        SupportRequest normal = SupportRequest.builder().id(UUID.randomUUID()).priority("normal").build();
        SupportRequest lost = SupportRequest.builder().id(UUID.randomUUID()).priority("high").build();
        when(requests.findTop200ByStatusOrderByLastActivityAtDesc(eq("open"))).thenReturn(List.of(normal, lost));

        assertThat(desk.inbox("open")).containsExactly(lost, normal);
    }
}
