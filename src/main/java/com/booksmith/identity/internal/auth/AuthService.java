package com.booksmith.identity.internal.auth;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.IdentityProperties;
import com.booksmith.identity.internal.security.JwtTokenProvider;
import com.booksmith.identity.internal.security.JwtTokenProvider.IssuedToken;
import com.booksmith.identity.internal.invite.InviteService;
import com.booksmith.identity.internal.mfa.MfaService;
import com.booksmith.identity.internal.security.OneTimeLoginCodes;
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
    /** Espera mínima entre dois envios do código de verificação. */
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final SessionRegistry sessionRegistry;
    private final EmailSender emailSender;
    private final SecurityProperties securityProperties;
    private final IdentityProperties identityProperties;
    private final LoginGuard loginGuard;
    private final OneTimeLoginCodes loginCodes;
    private final MfaService mfaService;
    private final InviteService inviteService;
    private final Clock clock;

    /**
     * Login com senha. Com 5 erros seguidos na conta, bloqueia por 15 minutos (429 {@code login_locked}). Com a
     * senha certa mas o e-mail ainda não confirmado, 403 {@code email_not_verified} (o app leva para a tela do
     * código). Aparelho novo gera aviso por e-mail.
     */
    @Transactional
    public Session login(String email, String password, String userAgent) {
        loginGuard.checkNotLocked(email);
        User found = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (found == null || found.getPasswordHash() == null || !passwordEncoder.matches(password, found.getPasswordHash())) {
            loginGuard.recordFailure(email, found);
            throw ApiException.unauthorized("E-mail ou senha inválidos.");
        }
        loginGuard.recordSuccess(email);
        if (!found.isEmailVerified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "email_not_verified",
                    "Confirme seu e-mail para entrar: digite o código que enviamos.");
        }
        requireNoMfa(found);
        return open(found, userAgent);
    }

    /** Com verificação em duas etapas, a senha (ou o Google) certa abre um desafio, não a sessão. */
    private void requireNoMfa(User user) {
        if (mfaService.enabled(user.getId())) {
            MfaService.Challenge challenge = mfaService.challenge(user.getId());
            throw new ApiException(HttpStatus.FORBIDDEN, "mfa_required", "Digite o código da verificação em duas etapas.",
                    java.util.Map.of("challenge", challenge.id(), "methods", challenge.methods()));
        }
    }

    /**
     * Segunda etapa do login: o código (app, e-mail ou recuperação) do desafio abre a sessão.
     *
     * @throws ApiException 401 {@code mfa_challenge_expired}; 422 {@code mfa_code_invalid}
     */
    @Transactional
    public Session completeMfa(String challenge, String code, String userAgent) {
        UUID userId = mfaService.verifyChallenge(challenge, code);
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.unauthorized("Usuário não encontrado."));
        return open(user, userAgent);
    }

    /** Segunda etapa por e-mail: manda o código do desafio. */
    public void sendMfaEmail(String challenge) {
        mfaService.sendChallengeEmail(challenge);
    }

    /**
     * Troca o código de uso único do login com Google pela sessão (o código vale 60 segundos e uma vez só).
     *
     * @throws ApiException 401 se o código não existe, venceu ou já foi usado
     */
    @Transactional
    public Session exchangeGoogleCode(String code, String userAgent) {
        User user = loginCodes.consume(code)
                .flatMap(userRepository::findById)
                .orElseThrow(() -> ApiException.unauthorized("O login com Google expirou. Tente de novo."));
        requireNoMfa(user);
        return open(user, userAgent);
    }

    private Session open(User user, String userAgent) {
        user.setLastLoginAt(Instant.now(clock));
        loginGuard.noteDevice(user, userAgent);
        log.info("Login realizado para userId: {}", user.getId());
        return new Session(user, tokenProvider.issue(user, userAgent));
    }

    /**
     * Manda um código novo para confirmar o e-mail. Responde igual exista ou não a conta (não revela cadastros);
     * só envia se a conta existe, ainda não foi confirmada e o último envio tem mais de 60 segundos.
     */
    @Transactional
    public void resendVerification(String email) {
        Instant now = Instant.now(clock);
        userRepository.findByEmailIgnoreCase(email)
                .filter(user -> !user.isEmailVerified())
                .filter(user -> user.getEmailVerificationExpiresAt() == null
                        || !now.isBefore(user.getEmailVerificationExpiresAt().minus(EMAIL_CODE_TTL).plus(RESEND_COOLDOWN)))
                .ifPresent(user -> {
                    String code = TokenHasher.randomCode(EMAIL_CODE_LENGTH);
                    user.setEmailVerificationCodeHash(TokenHasher.sha256(code));
                    user.setEmailVerificationExpiresAt(now.plus(EMAIL_CODE_TTL));
                    emailSender.sendEmailVerification(user.getEmail(), code);
                });
    }

    /**
     * Completa o cadastro de quem entrou pelo Google: data de nascimento (com a idade mínima) e nome de usuário.
     * Devolve um token novo na mesma sessão, já sem o cadastro pendente.
     *
     * @throws ApiException 409 {@code profile_already_complete}; 422 {@code age_below_minimum} (encerra as sessões)
     */
    @Transactional
    public Session completeProfile(UUID userId, String sessionId, LocalDate birthDate, String username) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Usuário não encontrado."));
        if (user.getBirthDate() != null) {
            throw ApiException.conflict("profile_already_complete", "O cadastro já está completo.", java.util.Map.of());
        }
        if (Period.between(birthDate, LocalDate.now(clock)).getYears() < identityProperties.minimumAge()) {
            sessionRegistry.endAll(userId, null);
            log.info("Cadastro pelo Google recusado pela idade mínima para userId: {}", userId);
            throw ApiException.unprocessable("age_below_minimum",
                    "É preciso ter pelo menos %d anos para usar o Booksmith.".formatted(identityProperties.minimumAge()));
        }
        user.setBirthDate(birthDate);
        if (username != null && !username.isBlank()) {
            user.setUsername(username.strip());
        }
        log.info("Cadastro completado para userId: {}", userId);
        return new Session(user, tokenProvider.reissue(user, sessionId));
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

        // Beta fechado: gasta um uso do convite (mesma transação: se o cadastro falhar, o uso volta)
        UUID invite = inviteService.redeem(registration.inviteCode());

        String code = TokenHasher.randomCode(EMAIL_CODE_LENGTH);
        User user = userRepository.save(User.builder()
                .inviteCodeId(invite)
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

    /** {@code inviteCode}: obrigatório no beta fechado. */
    public record Registration(String firstName, String lastName, String username, String email,
                               String password, LocalDate birthDate, String inviteCode) {

        public Registration(String firstName, String lastName, String username, String email, String password,
                            LocalDate birthDate) {
            this(firstName, lastName, username, email, password, birthDate, null);
        }
    }

    public record Session(User user, IssuedToken token) {
    }

    public record DeviceSession(String id, String device, Instant createdAt, Instant lastActiveAt,
                                boolean current) {
    }
}
