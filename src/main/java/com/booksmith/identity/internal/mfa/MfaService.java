package com.booksmith.identity.internal.mfa;

import com.booksmith.common.crypto.SecretBox;
import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.user.TokenHasher;
import com.booksmith.identity.internal.user.User;
import com.booksmith.identity.internal.user.UserRepository;
import com.booksmith.notification.api.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Verificação em duas etapas (MFA, opcional): app autenticador (TOTP) e código por e-mail, mais 10 códigos de
 * recuperação de uso único.
 *
 * <p><b>Login:</b> com a senha (ou o Google) certa, quem tem MFA recebe um <i>desafio</i> (Redis, 5 minutos, até 5
 * tentativas) em vez da sessão; a sessão só sai com um código válido ({@link #verifyChallenge}).
 *
 * <p><b>Configurar:</b> o método só vale depois de confirmado com um código. Ao ligar o primeiro, saem os códigos de
 * recuperação (mostrados uma vez). Desligar pede a senha (se a conta tem) e um código.
 */
@Slf4j
@Service
public class MfaService {

    static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    static final Duration EMAIL_CODE_TTL = Duration.ofMinutes(10);
    static final Duration EMAIL_RESEND = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;
    static final int RECOVERY_CODES = 10;

    private static final String CHALLENGE_PREFIX = "mfa:challenge:";
    private static final String CODE_PREFIX = "mfa:code:";
    /** Sem letras e números que se confundem (0/O, 1/I/L). */
    private static final String RECOVERY_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MfaFactorRepository factors;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final UserRepository users;
    private final StringRedisTemplate redis;
    private final SecretBox secretBox;
    private final EmailSender emailSender;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    MfaService(MfaFactorRepository factors, MfaRecoveryCodeRepository recoveryCodes, UserRepository users,
               StringRedisTemplate redis, SecretBox secretBox, EmailSender emailSender,
               PasswordEncoder passwordEncoder, Clock clock) {
        this.factors = factors;
        this.recoveryCodes = recoveryCodes;
        this.users = users;
        this.redis = redis;
        this.secretBox = secretBox;
        this.emailSender = emailSender;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** Métodos confirmados ({@code totp}, {@code email}) e quantos códigos de recuperação restam. */
    public record Status(boolean enabled, List<String> methods, long recoveryCodesLeft) {
    }

    /** Para o app autenticador: o segredo (para digitar) e o link {@code otpauth://} (para o QR code). */
    public record TotpSetup(String secret, String uri) {
    }

    /** O desafio do login: o app mostra a tela do código com os métodos da conta. */
    public record Challenge(String id, List<String> methods) {
    }

    @Transactional(readOnly = true)
    public Status status(UUID userId) {
        List<String> methods = confirmedMethods(userId);
        return new Status(!methods.isEmpty(), methods, methods.isEmpty() ? 0 : recoveryCodes.countByUserIdAndUsedAtIsNull(userId));
    }

    @Transactional(readOnly = true)
    public boolean enabled(UUID userId) {
        return factors.existsByUserIdAndConfirmedAtIsNotNull(userId);
    }

    // ===== Configurar =====

    /** Começa (ou recomeça) o app autenticador. 409 {@code mfa_method_active} se já está ligado. */
    @Transactional
    public TotpSetup startTotp(UUID userId) {
        User user = user(userId);
        MfaFactor factor = pending(userId, MfaFactor.TOTP);
        String secret = Totp.newSecret();
        factor.setSecret(secretBox.seal(secret));
        factor.setLastStep(null);
        factors.save(factor);
        return new TotpSetup(secret, Totp.uri(secret, user.getEmail()));
    }

    /** Começa o código por e-mail: manda um código para o e-mail da conta. */
    @Transactional
    public void startEmail(UUID userId) {
        User user = user(userId);
        factors.save(pending(userId, MfaFactor.EMAIL));
        sendCode(CODE_PREFIX + userId, user.getEmail());
    }

    /**
     * Confirma o método com um código dele. No primeiro método, devolve os códigos de recuperação (só desta vez).
     *
     * @throws ApiException 422 {@code mfa_code_invalid}; 409 {@code mfa_not_started}
     */
    @Transactional
    public List<String> confirm(UUID userId, String type, String code) {
        MfaFactor factor = factors.findByUserIdAndType(userId, type)
                .filter(f -> !f.confirmed())
                .orElseThrow(() -> ApiException.conflict("mfa_not_started", "Comece a configuração de novo.", Map.of()));
        boolean valid = MfaFactor.TOTP.equals(type)
                ? acceptTotp(factor, clean(code))
                : consumeEmailCode(CODE_PREFIX + userId, clean(code));
        if (!valid) {
            throw invalidCode();
        }
        boolean first = !enabled(userId);
        factor.setConfirmedAt(Instant.now(clock));
        User user = user(userId);
        log.info("MFA {} ativado para userId: {}", type, userId);
        emailSender.sendSecurityNotice(user.getEmail(), "Verificação em duas etapas ativada",
                (MfaFactor.TOTP.equals(type) ? "O app autenticador" : "O código por e-mail")
                        + " agora é pedido depois da senha para entrar na sua conta.");
        return first ? newRecoveryCodes(userId) : List.of();
    }

    /** Manda um código por e-mail para confirmar uma ação na conta logada (desativar, novos códigos). */
    @Transactional(readOnly = true)
    public void sendStepUpEmail(UUID userId) {
        if (factors.findByUserIdAndType(userId, MfaFactor.EMAIL).filter(MfaFactor::confirmed).isEmpty()) {
            throw ApiException.conflict("mfa_email_off", "O código por e-mail não está ativado.", Map.of());
        }
        sendCode(CODE_PREFIX + userId, user(userId).getEmail());
    }

    /** Novos códigos de recuperação (os antigos deixam de valer). Pede um código válido. */
    @Transactional
    public List<String> regenerateRecoveryCodes(UUID userId, String code) {
        requireEnabled(userId);
        if (!verifyAny(userId, clean(code), CODE_PREFIX + userId, false)) {
            throw invalidCode();
        }
        return newRecoveryCodes(userId);
    }

    /**
     * Desliga o MFA: pede a senha (se a conta tem) e um código. Tira todos os métodos e os códigos de recuperação.
     *
     * @throws ApiException 422 {@code password_invalid} ou {@code mfa_code_invalid}
     */
    @Transactional
    public void disable(UUID userId, String password, String code) {
        requireEnabled(userId);
        User user = user(userId);
        if (user.getPasswordHash() != null
                && (password == null || !passwordEncoder.matches(password, user.getPasswordHash()))) {
            throw ApiException.unprocessable("password_invalid", "Senha incorreta.");
        }
        if (!verifyAny(userId, clean(code), CODE_PREFIX + userId, true)) {
            throw invalidCode();
        }
        factors.deleteAllByUserId(userId);
        recoveryCodes.deleteAllByUserId(userId);
        log.info("MFA desativado para userId: {}", userId);
        emailSender.sendSecurityNotice(user.getEmail(), "Verificação em duas etapas desativada",
                "Sua conta voltou a entrar só com a senha (ou o Google). Dá para ativar de novo em Perfil.");
    }

    // ===== Login =====

    /** Abre o desafio do login para quem tem MFA. */
    public Challenge challenge(UUID userId) {
        String id = TokenHasher.randomUrlToken();
        String key = CHALLENGE_PREFIX + TokenHasher.sha256(id);
        redis.opsForHash().putAll(key, Map.of("userId", userId.toString(), "attempts", "0"));
        redis.expire(key, CHALLENGE_TTL);
        return new Challenge(id, confirmedMethods(userId));
    }

    /** No login, manda o código por e-mail (quem tem o método por e-mail). */
    @Transactional(readOnly = true)
    public void sendChallengeEmail(String challengeId) {
        UUID userId = challengeUser(challengeId);
        if (factors.findByUserIdAndType(userId, MfaFactor.EMAIL).filter(MfaFactor::confirmed).isEmpty()) {
            throw ApiException.conflict("mfa_email_off", "O código por e-mail não está ativado nesta conta.", Map.of());
        }
        sendCode(CODE_PREFIX + "login:" + TokenHasher.sha256(challengeId), user(userId).getEmail());
    }

    /**
     * Confere o código do login: do app, do e-mail ou de recuperação. Certo, o desafio acaba e devolve o usuário;
     * na quinta tentativa errada, o desafio acaba e é preciso entrar de novo.
     *
     * @throws ApiException 401 {@code mfa_challenge_expired}; 422 {@code mfa_code_invalid}
     */
    @Transactional
    public UUID verifyChallenge(String challengeId, String code) {
        UUID userId = challengeUser(challengeId);
        String key = CHALLENGE_PREFIX + TokenHasher.sha256(challengeId);
        if (verifyAny(userId, clean(code), CODE_PREFIX + "login:" + TokenHasher.sha256(challengeId), true)) {
            redis.delete(key);
            return userId;
        }
        Long attempts = redis.opsForHash().increment(key, "attempts", 1);
        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            redis.delete(key);
            log.warn("Desafio de MFA encerrado por {} códigos errados para userId: {}", MAX_ATTEMPTS, userId);
            throw expired("Muitos códigos errados. Entre de novo.");
        }
        throw invalidCode();
    }

    // ===== Por dentro =====

    /** App, e-mail ou (se permitido) código de recuperação. */
    private boolean verifyAny(UUID userId, String code, String emailKey, boolean allowRecovery) {
        if (code.length() == Totp.DIGITS) {
            Optional<MfaFactor> totp = factors.findByUserIdAndType(userId, MfaFactor.TOTP).filter(MfaFactor::confirmed);
            return totp.isPresent() && acceptTotp(totp.get(), code)
                    || factors.findByUserIdAndType(userId, MfaFactor.EMAIL).filter(MfaFactor::confirmed).isPresent()
                    && consumeEmailCode(emailKey, code);
        }
        return allowRecovery && useRecoveryCode(userId, code);
    }

    /** O mesmo passo de 30 s não vale duas vezes (código visto por alguém por cima do ombro, por exemplo). */
    private boolean acceptTotp(MfaFactor factor, String code) {
        OptionalLong step = Totp.match(secretBox.open(factor.getSecret()), code, Instant.now(clock));
        if (step.isEmpty() || factor.getLastStep() != null && step.getAsLong() <= factor.getLastStep()) {
            return false;
        }
        factor.setLastStep(step.getAsLong());
        return true;
    }

    private boolean useRecoveryCode(UUID userId, String code) {
        if (!code.matches("[A-Z0-9]{8}")) {
            return false;
        }
        Optional<MfaRecoveryCode> found = recoveryCodes.findByUserIdAndCodeHashAndUsedAtIsNull(userId, TokenHasher.sha256(code));
        found.ifPresent(recovery -> {
            recovery.setUsedAt(Instant.now(clock));
            long left = recoveryCodes.countByUserIdAndUsedAtIsNull(userId) - 1;
            log.info("Código de recuperação usado por userId: {} ({} restantes)", userId, left);
            emailSender.sendSecurityNotice(user(userId).getEmail(), "Código de recuperação usado",
                    "Um código de recuperação foi usado para entrar na sua conta. Restam %d.".formatted(left));
        });
        return found.isPresent();
    }

    private List<String> newRecoveryCodes(UUID userId) {
        recoveryCodes.deleteAllByUserId(userId);
        recoveryCodes.flush();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder code = new StringBuilder();
            for (int c = 0; c < 8; c++) {
                code.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            recoveryCodes.save(MfaRecoveryCode.builder().userId(userId).codeHash(TokenHasher.sha256(code.toString())).build());
            codes.add(code.substring(0, 4) + "-" + code.substring(4));
        }
        return codes;
    }

    /** Código de 6 números por e-mail, com hash no Redis; um envio por minuto. */
    private void sendCode(String key, String email) {
        String sentKey = key + ":sent";
        if (Boolean.TRUE.equals(redis.hasKey(sentKey))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "mfa_code_wait",
                    "Espere um minuto para pedir outro código.", Map.of("retryAfterSeconds", EMAIL_RESEND.toSeconds()));
        }
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(key, TokenHasher.sha256(code), EMAIL_CODE_TTL);
        redis.opsForValue().set(sentKey, "1", EMAIL_RESEND);
        emailSender.sendMfaCode(email, code);
    }

    private boolean consumeEmailCode(String key, String code) {
        String hash = redis.opsForValue().get(key);
        if (hash != null && java.security.MessageDigest.isEqual(hash.getBytes(), TokenHasher.sha256(code).getBytes())) {
            redis.delete(key);
            return true;
        }
        return false;
    }

    private MfaFactor pending(UUID userId, String type) {
        MfaFactor factor = factors.findByUserIdAndType(userId, type)
                .orElseGet(() -> MfaFactor.builder().userId(userId).type(type).build());
        if (factor.confirmed()) {
            throw ApiException.conflict("mfa_method_active", "Este método já está ativado.", Map.of());
        }
        return factor;
    }

    private List<String> confirmedMethods(UUID userId) {
        return factors.findAllByUserId(userId).stream().filter(MfaFactor::confirmed).map(MfaFactor::getType).sorted()
                .toList();
    }

    private UUID challengeUser(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            throw expired("Entre de novo.");
        }
        Object userId = redis.opsForHash().get(CHALLENGE_PREFIX + TokenHasher.sha256(challengeId), "userId");
        if (userId == null) {
            throw expired("O tempo para digitar o código acabou. Entre de novo.");
        }
        return UUID.fromString(userId.toString());
    }

    private void requireEnabled(UUID userId) {
        if (!enabled(userId)) {
            throw ApiException.conflict("mfa_off", "A verificação em duas etapas não está ativada.", Map.of());
        }
    }

    private User user(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Usuário não encontrado."));
    }

    /** Sem espaços e hífens, maiúsculo: "abcd-2345" e "123 456" valem. */
    static String clean(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static ApiException invalidCode() {
        return ApiException.unprocessable("mfa_code_invalid", "Código incorreto ou vencido.");
    }

    private static ApiException expired(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "mfa_challenge_expired", message, Map.of());
    }
}
