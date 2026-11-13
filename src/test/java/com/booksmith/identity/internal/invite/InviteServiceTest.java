package com.booksmith.identity.internal.invite;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.user.User;
import com.booksmith.identity.internal.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InviteServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T01:00:00Z");

    private final InviteCodeRepository invites = mock(InviteCodeRepository.class);
    private final UserRepository users = mock(UserRepository.class);

    private InviteService service(boolean required) {
        return new InviteService(invites, users, new InviteProperties(required), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void codeIsReadTheWayAPersonTypesIt() {
        assertThat(InviteService.normalize("beta-7kq2 m9xa")).isEqualTo("BETA-7KQ2-M9XA");
        assertThat(InviteService.normalize(" Beta7KQ2M9XA ")).isEqualTo("BETA-7KQ2-M9XA");
        assertThat(InviteService.normalize(null)).isEmpty();
    }

    @Test
    void closedBetaNeedsAnInviteWithUsesLeft() {
        UUID id = UUID.randomUUID();
        when(invites.use("BETA-7KQ2-M9XA", NOW)).thenReturn(1);
        when(invites.findByCode("BETA-7KQ2-M9XA")).thenReturn(Optional.of(InviteCode.builder().id(id).build()));
        when(invites.use("BETA-USAD-OOOO", NOW)).thenReturn(0);

        assertThat(service(true).redeem("beta 7kq2 m9xa")).isEqualTo(id);
        for (String bad : new String[] {null, "", "BETA-USAD-OOOO"}) {
            assertThatThrownBy(() -> service(true).redeem(bad))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("invite_invalid"));
        }
        // Beta aberto: sem código, passa
        assertThat(service(false).redeem(null)).isNull();
    }

    @Test
    void onlyAdminsCreateInvitesAndCodesLookRight() {
        UUID admin = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        when(users.findById(admin)).thenReturn(Optional.of(User.builder().id(admin).admin(true).build()));
        when(users.findById(author)).thenReturn(Optional.of(User.builder().id(author).build()));
        when(invites.save(any())).thenAnswer(i -> i.getArgument(0));

        var created = service(true).create(admin, 3, 5, " Discord ", 30);
        assertThat(created).hasSize(3).allSatisfy(invite -> {
            assertThat(invite.code()).matches("BETA-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}");
            assertThat(invite.maxUses()).isEqualTo(5);
            assertThat(invite.note()).isEqualTo("Discord");
            assertThat(invite.expiresAt()).isEqualTo(NOW.plusSeconds(30L * 86_400));
            assertThat(invite.active()).isTrue();
        });
        assertThatThrownBy(() -> service(true).create(author, 1, 1, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("admin_only"));
        assertThatThrownBy(() -> service(true).create(admin, 51, 1, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("invite_request_invalid"));
    }
}
