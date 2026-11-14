package com.booksmith.identity.internal.security;

import com.booksmith.identity.internal.user.User;
import com.booksmith.identity.internal.user.UserRepository;
import com.booksmith.notification.api.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2SuccessHandlerTest {

    private static final String APP = "http://app.test";

    private final UserRepository users = mock(UserRepository.class);
    private final OneTimeLoginCodes codes = mock(OneTimeLoginCodes.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final com.booksmith.identity.internal.invite.InviteService invites =
            mock(com.booksmith.identity.internal.invite.InviteService.class);
    private final OAuth2SuccessHandler handler = new OAuth2SuccessHandler(users, codes,
            new SecurityProperties("test-secret-with-at-least-32-characters!!", Duration.ofHours(1), Duration.ofDays(30),
                    APP, List.of()), emailSender, invites);

    @BeforeEach
    void setUp() {
        when(users.findByGoogleSubject(anyString())).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(i -> {
            User user = i.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });
        when(codes.create(any())).thenReturn("codigo");
    }

    @Test
    void linkFromTheProfileTiesTheGoogleAccountToTheTicketOwnerEvenWithAnotherEmail() throws Exception {
        User lia = passwordUser("lia@example.com");
        when(codes.consumeLinkTicket("ticket")).thenReturn(Optional.of(lia.getId()));
        when(users.findById(lia.getId())).thenReturn(Optional.of(lia));

        String target = finish("g-123", "lia.pessoal@gmail.com", "ticket");

        assertThat(target).isEqualTo(APP + "/profile?google=linked");
        assertThat(lia.isGoogleLinked()).isTrue();
        assertThat(lia.getGoogleSubject()).isEqualTo("g-123");
        assertThat(lia.getGoogleEmail()).isEqualTo("lia.pessoal@gmail.com");
        verify(emailSender).sendSecurityNotice(eq("lia@example.com"), eq("Sua conta foi ligada ao Google"),
                contains("lia.pessoal@gmail.com"));
        verify(codes, never()).create(any());
    }

    @Test
    void googleAccountAlreadyUsedByAnotherBooksmithAccountIsNotLinked() throws Exception {
        User lia = passwordUser("lia@example.com");
        User other = passwordUser("ana@example.com");
        when(codes.consumeLinkTicket("ticket")).thenReturn(Optional.of(lia.getId()));
        when(users.findById(lia.getId())).thenReturn(Optional.of(lia));
        when(users.findByGoogleSubject("g-ana")).thenReturn(Optional.of(other));

        assertThat(finish("g-ana", "ana@gmail.com", "ticket")).isEqualTo(APP + "/profile?google=in_use");
        assertThat(lia.isGoogleLinked()).isFalse();

        // Nem quando o e-mail Google é o login de outra conta
        when(codes.consumeLinkTicket("ticket2")).thenReturn(Optional.of(lia.getId()));
        when(users.findByEmailIgnoreCase("ana@example.com")).thenReturn(Optional.of(other));
        assertThat(finish("g-novo", "ana@example.com", "ticket2")).isEqualTo(APP + "/profile?google=in_use");
    }

    @Test
    void expiredTicketDoesNotLink() throws Exception {
        when(codes.consumeLinkTicket("velho")).thenReturn(Optional.empty());

        assertThat(finish("g-1", "x@gmail.com", "velho")).isEqualTo(APP + "/profile?google=expired");
    }

    @Test
    void passwordAccountNeverLinkedIsNotTakenOverByGoogleLogin() throws Exception {
        User lia = passwordUser("lia@example.com");
        when(users.findByEmailIgnoreCase("lia@example.com")).thenReturn(Optional.of(lia));

        assertThat(finish("g-123", "lia@example.com", null)).isEqualTo(APP + "/login?error=google_account_exists");
        assertThat(lia.isGoogleLinked()).isFalse();
        verify(codes, never()).create(any());
    }

    @Test
    void linkedAccountEntersByTheGoogleIdentityAndNewOnesAreCreated() throws Exception {
        User lia = passwordUser("lia@example.com");
        lia.setGoogleLinked(true);
        lia.setGoogleSubject("g-123");
        when(users.findByGoogleSubject("g-123")).thenReturn(Optional.of(lia));

        assertThat(finish("g-123", "lia.pessoal@gmail.com", null)).isEqualTo(APP + "/auth/callback#code=codigo");
        verify(codes).create(lia.getId());

        assertThat(finish("g-999", "nova@gmail.com", null)).isEqualTo(APP + "/auth/callback#code=codigo");
        verify(users).save(any(User.class));
    }

    @Test
    void newGoogleAccountWithoutAValidInviteIsNotCreated() throws Exception {
        // O convite é conferido antes de ser gasto (capturar a exceção do redeem dentro da transação dava 500)
        when(invites.required()).thenReturn(true);
        when(invites.valid(any())).thenReturn(false);

        assertThat(finish("g-novo", "nova@gmail.com", null)).isEqualTo(APP + "/login?error=google_invite_required");
        verify(users, never()).save(any(User.class));
    }

    @Test
    void accountLinkedBeforeTheGoogleIdentityWasStoredGetsIt() throws Exception {
        User legacy = User.builder().id(UUID.randomUUID()).email("g@example.com").googleLinked(true).build();
        when(users.findByEmailIgnoreCase("g@example.com")).thenReturn(Optional.of(legacy));

        assertThat(finish("g-777", "g@example.com", null)).isEqualTo(APP + "/auth/callback#code=codigo");
        assertThat(legacy.getGoogleSubject()).isEqualTo("g-777");
    }

    private static User passwordUser(String email) {
        return User.builder().id(UUID.randomUUID()).email(email).passwordHash("hash").emailVerified(true).build();
    }

    /** A volta do Google com a conta dada; devolve para onde o app foi mandado. */
    private String finish(String subject, String email, String linkTicket) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (linkTicket != null) {
            request.getSession(true).setAttribute(GoogleLinkIntentFilter.ATTRIBUTE, linkTicket);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
                Map.of("sub", subject, "email", email, "email_verified", true, "name", "Lia Moreau"), "sub");
        handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken(user, null));
        return response.getRedirectedUrl();
    }
}
