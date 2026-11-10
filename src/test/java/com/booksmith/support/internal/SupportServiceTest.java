package com.booksmith.support.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.AuthenticatedUser;
import com.booksmith.identity.api.UserDirectory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportServiceTest {

    private final SupportRequestRepository repository = mock(SupportRequestRepository.class);
    private final HelpdeskGateway helpdesk = mock(HelpdeskGateway.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final SupportService service = new SupportService(repository, helpdesk, users, JsonMapper.builder().build(),
            Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC));

    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "sergio", "Sérgio", "s1");

    SupportServiceTest() {
        when(users.email(user.id())).thenReturn(Optional.of("autor@exemplo.com"));
        when(repository.save(any())).thenAnswer(i -> {
            SupportRequest r = i.getArgument(0);
            r.setId(UUID.fromString("0196f000-0000-7000-8000-00003f9a2c71"));
            return r;
        });
        when(helpdesk.open(any())).thenReturn("TICKET-1");
    }

    @Test
    void lostTextIsHighPriorityAndAnsweredAtTheAccountEmail() {
        SupportRequest request = service.create(user, "lost_text", "  Meu capítulo 3 sumiu depois de sincronizar.  ",
                null);

        assertThat(request.getPriority()).isEqualTo("high");
        assertThat(request.getEmail()).isEqualTo("autor@exemplo.com");
        assertThat(request.getMessage()).isEqualTo("Meu capítulo 3 sumiu depois de sincronizar.");
        assertThat(request.getHelpdeskRef()).isEqualTo("TICKET-1");
        assertThat(request.protocol()).isEqualTo("BS-3F9A2C71");
        verify(helpdesk).open(request);
    }

    @Test
    void contextKeepsOnlyKnownFieldsCutToSize() {
        var context = new SupportContext("/books/1".repeat(100), "1.0", null, true, "390x844", "pt-BR",
                List.of("trace-abc-123", "não é trace!", "trace-abc-123"), null, "saved");

        SupportRequest request = service.create(user, "bug", "O botão de exportar não responde.", context);

        assertThat(request.getContext())
                .contains("\"traceIds\":[\"trace-abc-123\"]")
                .contains("\"screen\":\"390x844\"")
                .doesNotContain("não é trace");
        assertThat(request.getContext().length()).isLessThan(600);
        assertThat(request.getPriority()).isEqualTo("normal");
    }

    @Test
    void refusesUnknownSubjectTooShortMessageAndFlooding() {
        assertThatThrownBy(() -> service.create(user, "refund", "Quero meu dinheiro de volta.", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("support_invalid"));
        assertThatThrownBy(() -> service.create(user, "bug", "socorro", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("support_invalid"));

        when(repository.countByUserIdAndCreatedAtAfter(any(), any())).thenReturn((long) SupportService.MAX_PER_DAY);
        assertThatThrownBy(() -> service.create(user, "bug", "Mais um pedido, de novo.", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("too_many_support_requests"));
    }

    @Test
    void listsOnlyMine() {
        when(repository.findTop50ByUserIdOrderByCreatedAtDesc(user.id())).thenReturn(Collections.emptyList());
        assertThat(service.mine(user.id())).isEmpty();
    }
}
