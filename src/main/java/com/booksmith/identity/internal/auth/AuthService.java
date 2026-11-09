package com.booksmith.identity.internal.auth;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.IdentityProperties;
import com.booksmith.identity.internal.security.JwtTokenProvider;
import com.booksmith.identity.internal.security.JwtTokenProvider.IssuedToken;
import com.booksmith.identity.internal.security.SecurityProperties;
import com.booksmith.identity.internal.security.SessionRegistry;
import com.booksmith.identity.internal.user.AuthProvider;
import com.booksmith.identity.internal.user.PasswordResetToken;
import com.booksmith.identity.internal.user.PasswordResetTokenRepository;
import com.booksmith.identity.internal.user.TokenHasher;
import com.booksmith.identity.internal.user.User;
import com.booksmith.identity.internal.user.UserRepository;
import com.booksmith.notification.api.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Duration EMAIL_CODE_TTL = Duration.ofMinutes(15);
    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30);
    private static final int EMAIL_CODE_LENGTH = 6;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final SessionRegistry sessionRegistry;
    private final EmailSender emailSender;
    private final SecurityProperties securityProperties;
    private final IdentityProperties identityProperties;
    private final Clock clock;

    @Transactional
    public Session login(String email, String password, String userAgent) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .filter(u -> u.getPasswordHash() != null && passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("E-mail ou senha inválidos."));

        user.setLastLoginAt(Instant.now(clock));
        log.info("Login realizado para userId: {}", user.getId());
        return new Session(user, tokenProvider.issue(user, userAgent));
    }

    @Transactional
    public void register(Registration registration) {
        if (userRepository.existsByEmailIgnoreCase(registration.email())) {
            throw ApiException.conflict("email_in_use", "Já existe uma conta com este e-mail.", java.util.Map.of());
        }

        LocalDate today = LocalDate.now(clock);
        if (Period.between(registration.birthDate(), today).getYears() < identityProperties.minimumAge()) {
            throw ApiException.unprocessable("age_below_minimum",
                    "É preciso ter pelo menos %d anos para criar uma conta.".formatted(identityProperties.minimumAge()));
        }

        String code = TokenHasher.randomCode(EMAIL_CODE_LENGTH);
        User user = userRepository.save(User.builder()
                .firstName(registration.firstName())
                .lastName(registration.lastName())
                .username(registration.username())
                .email(registration.email().trim())
                .passwordHash(passwordEncoder.encode(registration.password()))
                .birthDate(registration.birthDate())
                .provider(AuthProvider.LOCAL)
                .emailVerificationCodeHash(TokenHasher.sha256(code))
                .emailVerificationExpiresAt(Instant.now(clock).plus(EMAIL_CODE_TTL))
                .build());

        emailSender.sendEmailVerification(user.getEmail(), code);
        log.info("Cadastro realizado para userId: {}", user.getId());
    }

    @Transactional
    public void verifyEmail(String email, String code) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(AuthService::invalidVerificationCode);
        if (user.isEmailVerified()) {
            return;
        }

        boolean matches = user.getEmailVerificationCodeHash() != null
                && user.getEmailVerificationCodeHash().equals(TokenHasher.sha256(code.toUpperCase()));
        boolean valid = user.getEmailVerificationExpiresAt() != null
                && Instant.now(clock).isBefore(user.getEmailVerificationExpiresAt());
        if (!matches || !valid) {
            throw invalidVerificationCode();
        }

        user.setEmailVerified(true);
        user.setEmailVerificationCodeHash(null);
        user.setEmailVerificationExpiresAt(null);
    }

    @Transactional(readOnly = true)
    public Session refresh(String token) {
        var candidate = tokenProvider.verifyForRefresh(token);
        User user = userRepository.findById(candidate.userId())
                .orElseThrow(() -> ApiException.unauthorized("Usuário não encontrado."));
        return new Session(user, tokenProvider.refresh(candidate, user));
    }

    public void logout(String token) {
        tokenProvider.logout(token);
    }

    /** Sessões abertas do usuário: a atual primeiro, depois da usada mais recentemente para a mais antiga. */
    public List<DeviceSession> sessions(UUID userId, String currentSessionId) {
        return sessionRegistry.list(userId).stream()
                .map(info -> new DeviceSession(info.id(), DeviceLabel.describe(info.userAgent()),
                        info.createdAt(), info.lastActiveAt(), info.id().equals(currentSessionId)))
                .sorted(Comparator.comparing(DeviceSession::current).reversed())
                .toList();
    }

    public void endSession(UUID userId, String sessionId) {
        if (!sessionRegistry.end(userId, sessionId)) {
            throw ApiException.notFound("session_not_found", "Sessão não encontrada.");
        }
        log.info("Sessão encerrada pelo usuário userId: {}", userId);
    }

    /** "Sair de todos os outros aparelhos": mantém só a sessão de onde veio o pedido. */
    public int endOtherSessions(UUID userId, String currentSessionId) {
        int ended = sessionRegistry.endAll(userId, currentSessionId);
        log.info("{} sessões encerradas pelo usuário userId: {}", ended, userId);
        return ended;
    }

    /** Sempre responde igual, exista ou não a conta, para não revelar e-mails cadastrados. */
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            String token = TokenHasher.randomUrlToken();
            passwordResetTokenRepository.save(PasswordResetToken.builder()
                    .userId(user.getId())
                    .tokenHash(TokenHasher.sha256(token))
                    .expiresAt(Instant.now(clock).plus(RESET_TOKEN_TTL))
                    .build());
            emailSender.sendPasswordReset(user.getEmail(),
                    securityProperties.frontendUrl() + "/reset-password?token=" + token);
        });
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        Instant now = Instant.now(clock);
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(TokenHasher.sha256(token))
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> ApiException.unprocessable("reset_token_invalid",
                        "Link de redefinição inválido ou expirado. Peça um novo."));

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> ApiException.unprocessable("reset_token_invalid", "Conta não encontrada."));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        resetToken.setUsedAt(now);
        // Quem redefine a senha pode estar recuperando a conta: nenhum aparelho continua conectado
        int ended = sessionRegistry.endAll(user.getId(), null);
        log.info("Senha redefinida para userId: {}; {} sessões encerradas", user.getId(), ended);
    }

    private static ApiException invalidVerificationCode() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "verification_code_invalid",
                "Código de verificação inválido ou expirado.");
    }

    public record Registration(String firstName, String lastName, String username, String email,
                               String password, LocalDate birthDate) {
    }

    public record Session(User user, IssuedToken token) {
    }

    public record DeviceSession(String id, String device, Instant createdAt, Instant lastActiveAt,
                                boolean current) {
    }
}
