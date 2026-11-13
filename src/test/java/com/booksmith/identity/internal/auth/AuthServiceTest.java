package com.booksmith.identity.internal.auth;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.IdentityProperties;
import com.booksmith.identity.internal.security.JwtTokenProvider;
import com.booksmith.identity.internal.security.OneTimeLoginCodes;
import com.booksmith.identity.internal.security.SecurityProperties;
import com.booksmith.identity.internal.security.SessionRegistry;
import com.booksmith.identity.internal.user.PasswordResetToken;
import com.booksmith.identity.internal.user.PasswordResetTokenRepository;
import com.booksmith.identity.internal.user.TokenHasher;
import com.booksmith.identity.internal.user.User;
import com.booksmith.identity.internal.user.UserRepository;
import com.booksmith.notification.api.EmailSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordResetTokenRepository resetTokens = mock(PasswordResetTokenRepository.class);
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    private final LoginGuard loginGuard = mock(LoginGuard.class);
    private final OneTimeLoginCodes loginCodes = mock(OneTimeLoginCodes.class);
    private final com.booksmith.identity.internal.invite.InviteService inviteService =
            mock(com.booksmith.identity.internal.invite.InviteService.class);
    private final com.booksmith.identity.internal.mfa.MfaService mfaService = mock(com.booksmith.identity.internal.mfa.MfaService.class);
    private final com.booksmith.identity.internal.terms.TermsService termsService =
            mock(com.booksmith.identity.internal.terms.TermsService.class);

    private final AuthService service = new AuthService(userRepository, resetTokens, passwordEncoder, tokenProvider,
            sessionRegistry, emailSender,
            new SecurityProperties("test-secret-with-at-least-32-characters!!", Duration.ofHours(1),
                    Duration.ofDays(30), "http://app.test", List.of()),
            new IdentityProperties(13),
            loginGuard, loginCodes, mfaService, inviteService, termsService,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void registerRejectsUsersBelowMinimumAge() {
        var registration = registration(LocalDate.of(2015, 1, 1));

        assertThatThrownBy(() -> service.register(registration))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("age_below_minimum"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerHashesPasswordAndSendsVerificationCode() {
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.register(registration(LocalDate.of(2005, 5, 20)));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("Senha@123");
        assertThat(passwordEncoder.matches("Senha@123", saved.getValue().getPasswordHash())).isTrue();

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmailVerification(eq("lia@example.com"), code.capture());
        assertThat(saved.getValue().getEmailVerificationCodeHash()).isEqualTo(TokenHasher.sha256(code.getValue()));
    }

    @Test
    void loginFailsWithSameMessageForUnknownEmailAndWrongPassword() {
        when(userRepository.findByEmailIgnoreCase("x@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("lia@example.com")).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).passwordHash(passwordEncoder.encode("Senha@123")).build()));

        assertThatThrownBy(() -> service.login("x@example.com", "Senha@123", null)).hasMessage("E-mail ou senha inválidos.");
        assertThatThrownBy(() -> service.login("lia@example.com", "errada", null)).hasMessage("E-mail ou senha inválidos.");
    }

    @Test
    void wrongPasswordsAreCountedPerAccountAndALockedAccountIsNotEvenChecked() {
        User lia = User.builder().id(UUID.randomUUID()).email("lia@example.com")
                .passwordHash(passwordEncoder.encode("Senha@123")).emailVerified(true).build();
        when(userRepository.findByEmailIgnoreCase("lia@example.com")).thenReturn(Optional.of(lia));

        assertThatThrownBy(() -> service.login("lia@example.com", "errada", null)).isInstanceOf(ApiException.class);
        verify(loginGuard).recordFailure("lia@example.com", lia);

        org.mockito.Mockito.doThrow(new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "login_locked", "x"))
                .when(loginGuard).checkNotLocked("lia@example.com");
        assertThatThrownBy(() -> service.login("lia@example.com", "Senha@123", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("login_locked"));
        verify(tokenProvider, never()).issue(any(), any());
    }

    @Test
    void rightPasswordWithUnconfirmedEmailAsksForTheCode() {
        User lia = User.builder().id(UUID.randomUUID()).email("lia@example.com")
                .passwordHash(passwordEncoder.encode("Senha@123")).build();
        when(userRepository.findByEmailIgnoreCase("lia@example.com")).thenReturn(Optional.of(lia));

        assertThatThrownBy(() -> service.login("lia@example.com", "Senha@123", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("email_not_verified"));
        // A senha estava certa: os erros zeram, mas não abre sessão
        verify(loginGuard).recordSuccess("lia@example.com");
        verify(tokenProvider, never()).issue(any(), any());
    }

    @Test
    void successfulLoginNotesTheDevice() {
        User lia = User.builder().id(UUID.randomUUID()).email("lia@example.com")
                .passwordHash(passwordEncoder.encode("Senha@123")).emailVerified(true).build();
        when(userRepository.findByEmailIgnoreCase("lia@example.com")).thenReturn(Optional.of(lia));

        service.login("lia@example.com", "Senha@123", "Firefox");

        verify(loginGuard).noteDevice(lia, "Firefox");
        verify(tokenProvider).issue(lia, "Firefox");
    }

    @Test
    void googleCodeWorksOnceAndOnlyWhileValid() {
        User lia = User.builder().id(UUID.randomUUID()).email("lia@example.com").emailVerified(true).build();
        when(loginCodes.consume("bom")).thenReturn(Optional.of(lia.getId()));
        when(loginCodes.consume("usado")).thenReturn(Optional.empty());
        when(userRepository.findById(lia.getId())).thenReturn(Optional.of(lia));

        service.exchangeGoogleCode("bom", "Chrome");
        verify(tokenProvider).issue(lia, "Chrome");
        assertThatThrownBy(() -> service.exchangeGoogleCode("usado", "Chrome")).isInstanceOf(ApiException.class);
    }

    @Test
    void withTwoStepVerificationThePasswordOrGoogleOpensAChallengeNotASession() {
        User lia = User.builder().id(UUID.randomUUID()).email("lia@example.com")
                .passwordHash(passwordEncoder.encode("Senha@123")).emailVerified(true).build();
        when(userRepository.findByEmailIgnoreCase("lia@example.com")).thenReturn(Optional.of(lia));
        when(userRepository.findById(lia.getId())).thenReturn(Optional.of(lia));
        when(loginCodes.consume("google")).thenReturn(Optional.of(lia.getId()));
        when(mfaService.enabled(lia.getId())).thenReturn(true);
        when(mfaService.challenge(lia.getId()))
                .thenReturn(new com.booksmith.identity.internal.mfa.MfaService.Challenge("desafio", List.of("totp")));

        for (Runnable login : List.<Runnable>of(() -> service.login("lia@example.com", "Senha@123", null),
                () -> service.exchangeGoogleCode("google", null))) {
            assertThatThrownBy(login::run).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getCode()).isEqualTo("mfa_required");
                assertThat(e.getProperties()).containsEntry("challenge", "desafio");
            });
        }
        verify(tokenProvider, never()).issue(any(), any());

        when(mfaService.verifyChallenge("desafio", "123456")).thenReturn(lia.getId());
        service.completeMfa("desafio", "123456", "Chrome");
        verify(tokenProvider).issue(lia, "Chrome");
        verify(loginGuard).noteDevice(lia, "Chrome");
    }

    @Test
    void resendVerificationWaitsAMinuteAndIgnoresConfirmedOrUnknownAccounts() {
        User recent = User.builder().email("a@example.com").emailVerificationExpiresAt(NOW.plus(Duration.ofMinutes(15))).build();
        User old = User.builder().email("b@example.com").emailVerificationExpiresAt(NOW.plus(Duration.ofMinutes(10))).build();
        User done = User.builder().email("c@example.com").emailVerified(true).build();
        when(userRepository.findByEmailIgnoreCase("a@example.com")).thenReturn(Optional.of(recent));
        when(userRepository.findByEmailIgnoreCase("b@example.com")).thenReturn(Optional.of(old));
        when(userRepository.findByEmailIgnoreCase("c@example.com")).thenReturn(Optional.of(done));
        when(userRepository.findByEmailIgnoreCase("x@example.com")).thenReturn(Optional.empty());

        for (String email : List.of("a@example.com", "b@example.com", "c@example.com", "x@example.com")) {
            service.resendVerification(email);
        }

        verify(emailSender).sendEmailVerification(eq("b@example.com"), anyString());
        verify(emailSender, never()).sendEmailVerification(eq("a@example.com"), anyString());
        verify(emailSender, never()).sendEmailVerification(eq("c@example.com"), anyString());
        assertThat(old.getEmailVerificationExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void googleAccountCompletesTheProfileWithTheMinimumAge() {
        User google = User.builder().id(UUID.randomUUID()).email("g@example.com").username("autor.1234").build();
        when(userRepository.findById(google.getId())).thenReturn(Optional.of(google));

        assertThatThrownBy(() -> service.completeProfile(google.getId(), "s1", LocalDate.of(2016, 1, 1), null, true))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("age_below_minimum"));
        assertThat(google.getBirthDate()).isNull();
        verify(sessionRegistry).endAll(google.getId(), null);

        service.completeProfile(google.getId(), "s1", LocalDate.of(1990, 3, 4), "lia.moreau", true);
        assertThat(google.getBirthDate()).isEqualTo(LocalDate.of(1990, 3, 4));
        assertThat(google.getUsername()).isEqualTo("lia.moreau");
        verify(termsService).accept(google);
        verify(tokenProvider).reissue(google, "s1");

        assertThatThrownBy(() -> service.completeProfile(google.getId(), "s1", LocalDate.of(1990, 3, 4), null, true))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("profile_already_complete"));
    }

    @Test
    void creatingAnAccountRequiresAcceptingTheTerms() {
        org.mockito.Mockito.doCallRealMethod().when(termsService).requireAccepted(any());
        var withoutTerms = new AuthService.Registration("Lia", "Souza", "lia", "lia@example.com", "Senha@123",
                LocalDate.of(1990, 3, 4), null, false);

        assertThatThrownBy(() -> service.register(withoutTerms))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("terms_required"));
        verify(userRepository, never()).save(any());

        User google = User.builder().id(UUID.randomUUID()).email("g@example.com").username("autor.1234").build();
        when(userRepository.findById(google.getId())).thenReturn(Optional.of(google));
        assertThatThrownBy(() -> service.completeProfile(google.getId(), "s1", LocalDate.of(1990, 3, 4), null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("terms_required"));
        assertThat(google.getBirthDate()).isNull();
    }

    @Test
    void googleOnlyAccountCannotLoginWithPassword() {
        when(userRepository.findByEmailIgnoreCase("g@example.com"))
                .thenReturn(Optional.of(User.builder().id(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> service.login("g@example.com", "qualquer", null)).isInstanceOf(ApiException.class);
    }

    @Test
    void forgotPasswordForUnknownEmailDoesNothing() {
        when(userRepository.findByEmailIgnoreCase("ninguem@example.com")).thenReturn(Optional.empty());

        service.forgotPassword("ninguem@example.com");

        verifyNoInteractions(emailSender, resetTokens);
    }

    @Test
    void resetPasswordRejectsExpiredToken() {
        when(resetTokens.findByTokenHash(TokenHasher.sha256("token"))).thenReturn(Optional.of(
                PasswordResetToken.builder().userId(UUID.randomUUID()).expiresAt(NOW.minusSeconds(1)).build()));

        assertThatThrownBy(() -> service.resetPassword("token", "Nova@1234"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("reset_token_invalid"));
    }

    @Test
    void resetPasswordUpdatesHashAndConsumesToken() {
        User user = User.builder().id(UUID.randomUUID()).build();
        PasswordResetToken token = PasswordResetToken.builder().userId(user.getId()).expiresAt(NOW.plusSeconds(60)).build();
        when(resetTokens.findByTokenHash(TokenHasher.sha256("token"))).thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.resetPassword("token", "Nova@1234");

        assertThat(passwordEncoder.matches("Nova@1234", user.getPasswordHash())).isTrue();
        assertThat(token.getUsedAt()).isEqualTo(NOW);
        verify(emailSender, never()).sendPasswordReset(anyString(), anyString());
        // Recuperar a conta desconecta todos os aparelhos, inclusive o de quem tinha a senha antiga
        verify(sessionRegistry).endAll(eq(user.getId()), isNull());
    }

    @Test
    void sessionsMarkTheCurrentOneAndNameTheDevice() {
        UUID userId = UUID.randomUUID();
        when(sessionRegistry.list(userId)).thenReturn(List.of(
                new SessionRegistry.SessionInfo("a", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) "
                        + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1", NOW, NOW),
                new SessionRegistry.SessionInfo("b", null, null, null)));

        var sessions = service.sessions(userId, "b");

        // A atual vem primeiro, mesmo sendo a usada há mais tempo
        assertThat(sessions).extracting(AuthService.DeviceSession::device)
                .containsExactly("Aparelho desconhecido", "Safari no iPhone");
        assertThat(sessions).extracting(AuthService.DeviceSession::current).containsExactly(true, false);
    }

    @Test
    void endingSomeoneElsesSessionIsNotFound() {
        UUID userId = UUID.randomUUID();
        when(sessionRegistry.end(userId, "alheia")).thenReturn(false);

        assertThatThrownBy(() -> service.endSession(userId, "alheia"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("session_not_found"));
    }

    private static AuthService.Registration registration(LocalDate birthDate) {
        return new AuthService.Registration("Lia", "Souza", "lia", "lia@example.com", "Senha@123", birthDate);
    }
}
