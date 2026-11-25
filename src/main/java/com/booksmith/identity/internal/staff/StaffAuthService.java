package com.booksmith.identity.internal.staff;

import com.booksmith.common.alerts.SecurityIncident;
import com.booksmith.common.crypto.SecretBox;
import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.mfa.Totp;
import com.booksmith.identity.internal.security.BreachedPasswords;
import com.booksmith.identity.internal.user.TokenHasher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Login da equipe: e-mail e senha, depois o código do app autenticador (sempre; não há código por e-mail: quem invade
 * o e-mail não entra no backoffice). Cinco senhas erradas bloqueiam o e-mail por 15 minutos; cinco códigos errados
 * encerram o desafio. Também o aceite do convite: senha nova, autenticador e códigos de recuperação.
 */
@Slf4j
@Service
public class StaffAuthService {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);
    static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    static final int RECOVERY_CODES = 10;
    /** Senha da equipe: 12 caracteres ou mais, com letra e número. */
    static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*[0-9])\\S{12,128}$");
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String FAILURES = "staff:fail:";
    private static final String CHALLENGE = "staff:challenge:";

    private final StaffStore store;
    private final StaffTokens tokens;
    private final PasswordEncoder passwords;
    private final SecretBox secretBox;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final StaffProperties properties;
    private final Clock clock;
    private final BreachedPasswords breachedPasswords;
    /** Para o login com conta inexistente gastar o mesmo tempo do bcrypt (não revela quem é da equipe). */
    private final String dummyHash;

    StaffAuthService(StaffStore store, StaffTokens tokens, PasswordEncoder passwords, SecretBox secretBox,
                     StringRedisTemplate redis, ApplicationEventPublisher events, StaffProperties properties,
                     Clock clock, BreachedPasswords breachedPasswords) {
        this.store = store;
        this.tokens = tokens;
        this.passwords = passwords;
        this.secretBox = secretBox;
        this.redis = redis;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.breachedPasswords = breachedPasswords;
        this.dummyHash = passwords.encode(TokenHasher.randomUrlToken());
    }

    /** Senha certa: devolve o desafio do código (vale 5 minutos). Errada: 401 igual para conta inexistente. */
    public String login(String email, String password) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        String failures = FAILURES + TokenHasher.sha256(normalized);
        String count = redis.opsForValue().get(failures);
        if (count != null && Integer.parseInt(count) >= MAX_FAILURES) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "login_locked",
                    "Muitas tentativas erradas. Espere 15 minutos.", Map.of());
        }
        if (!properties.allowed(normalized)) {
            // E-mail de fora do domínio no login da equipe: nunca entra (nem conta antiga, se a regra mudou)
            events.publishEvent(new SecurityIncident(SecurityIncident.Kind.STAFF_DOMAIN_REJECTED, "login"));
            log.warn("Backoffice: login com e-mail de fora do domínio ({})", StaffService.domain(normalized));
        }
        var member = store.byEmail(normalized).filter(StaffMember::canLogIn).filter(m -> properties.allowed(m.email()));
        boolean ok = passwords.matches(password == null ? "" : password,
                member.map(StaffMember::passwordHash).orElse(dummyHash)) && member.isPresent();
        if (!ok) {
            Long now = redis.opsForValue().increment(failures);
            redis.expire(failures, LOCK);
            if (now != null && now == MAX_FAILURES) {
                events.publishEvent(new SecurityIncident(SecurityIncident.Kind.LOGIN_LOCKED, "staff"));
                log.warn("Backoffice: login bloqueado por {} senhas erradas", MAX_FAILURES);
            }
            throw ApiException.unauthorized("E-mail ou senha incorretos.");
        }
        redis.delete(failures);
        String challenge = TokenHasher.randomUrlToken();
        redis.opsForValue().set(CHALLENGE + TokenHasher.sha256(challenge), member.get().id() + "|0", CHALLENGE_TTL);
        return challenge;
    }

    /** O código do autenticador (ou um de recuperação) troca o desafio pela sessão. */
    @Transactional
    public Session verify(String challenge, String code) {
        String key = CHALLENGE + TokenHasher.sha256(challenge == null ? "" : challenge);
        String value = redis.opsForValue().get(key);
        if (value == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "mfa_challenge_expired",
                    "O tempo para digitar o código acabou. Entre de novo.", Map.of());
        }
        String[] parts = value.split("\\|");
        UUID memberId = UUID.fromString(parts[0]);
        StaffMember member = store.byId(memberId).filter(StaffMember::canLogIn).filter(m -> properties.allowed(m.email()))
                .orElseThrow(() -> ApiException.unauthorized("Conta desativada."));
        String clean = code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!codeMatches(member, clean)) {
            int attempts = Integer.parseInt(parts[1]) + 1;
            if (attempts >= MAX_FAILURES) {
                redis.delete(key);
                events.publishEvent(new SecurityIncident(SecurityIncident.Kind.MFA_FAILED, "staff"));
                throw new ApiException(HttpStatus.UNAUTHORIZED, "mfa_challenge_expired",
                        "Códigos errados demais. Entre de novo.", Map.of());
            }
            redis.opsForValue().set(key, memberId + "|" + attempts, CHALLENGE_TTL);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "mfa_code_invalid", "Código incorreto.", Map.of());
        }
        redis.delete(key);
        store.loggedIn(memberId, Instant.now(clock));
        store.audit(memberId, member.email(), "staff.login", null, null, Instant.now(clock));
        StaffTokens.Issued issued = tokens.open(member);
        return new Session(issued.accessToken(), issued.refreshToken());
    }

    public Session refresh(String refreshToken) {
        StaffTokens.Owner owner = tokens.owner(refreshToken);
        StaffMember member = store.byId(owner.memberId()).filter(StaffMember::canLogIn)
                .filter(m -> properties.allowed(m.email()))
                .orElseThrow(() -> ApiException.unauthorized("Sessão expirada. Entre novamente."));
        StaffTokens.Issued issued = tokens.refresh(member, owner, refreshToken);
        return new Session(issued.accessToken(), issued.refreshToken());
    }

    public void logout(String refreshToken, String accessToken) {
        tokens.close(refreshToken, accessToken);
    }

    /** O convite do link: quem é e o segredo do autenticador para o QR code (gerado na primeira vez que abre). */
    @Transactional
    public InviteView invite(String token) {
        String hash = TokenHasher.sha256(token == null ? "" : token);
        StaffStore.Invite invite = store.openInvite(hash, Instant.now(clock)).orElseThrow(StaffAuthService::inviteInvalid);
        StaffMember member = store.byId(invite.memberId()).orElseThrow(StaffAuthService::inviteInvalid);
        String secret;
        if (invite.sealedSecret() == null) {
            secret = Totp.newSecret();
            store.inviteSecret(hash, secretBox.seal(secret));
        } else {
            secret = secretBox.open(invite.sealedSecret());
        }
        return new InviteView(member.email(), member.name(), member.role().name(), secret,
                Totp.uri(secret, "Equipe " + member.email()));
    }

    /**
     * Aceita o convite: senha nova e o primeiro código do autenticador (prova que ele foi configurado). Devolve os
     * códigos de recuperação (mostrados uma vez só).
     */
    @Transactional
    public List<String> accept(String token, String password, String code) {
        String hash = TokenHasher.sha256(token == null ? "" : token);
        Instant now = Instant.now(clock);
        StaffStore.Invite invite = store.openInvite(hash, now).orElseThrow(StaffAuthService::inviteInvalid);
        if (invite.sealedSecret() == null) {
            throw inviteInvalid();
        }
        if (password == null || !PASSWORD.matcher(password).matches()) {
            throw ApiException.unprocessable("staff_password_weak",
                    "A senha precisa de 12 caracteres ou mais, com letras e números, sem espaços.");
        }
        breachedPasswords.requireNotBreached(password);
        String secret = secretBox.open(invite.sealedSecret());
        OptionalLong step = Totp.match(secret, code == null ? "" : code.replaceAll("\\s", ""), now);
        if (step.isEmpty()) {
            throw ApiException.unprocessable("mfa_code_invalid",
                    "Código incorreto. Confira se o app autenticador leu o QR code e digite o código atual.");
        }
        if (!store.useInvite(hash, now)) {
            throw inviteInvalid();
        }
        StaffMember member = store.byId(invite.memberId()).orElseThrow(StaffAuthService::inviteInvalid);
        tokens.closeAll(member.id());
        store.credentials(member.id(), passwords.encode(password), invite.sealedSecret(), now);
        store.useStep(member.id(), step.getAsLong());
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder raw = new StringBuilder();
            for (int c = 0; c < 8; c++) {
                raw.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            hashes.add(TokenHasher.sha256(raw.toString()));
            codes.add(raw.substring(0, 4) + "-" + raw.substring(4));
        }
        store.recoveryCodes(member.id(), hashes);
        store.audit(member.id(), member.email(), "staff.activate", null, null, now);
        log.info("Backoffice: conta da equipe {} ativada", member.id());
        return codes;
    }

    private boolean codeMatches(StaffMember member, String code) {
        Instant now = Instant.now(clock);
        if (code.matches("\\d{6}")) {
            OptionalLong step = Totp.match(secretBox.open(member.totpSecret()), code, now);
            return step.isPresent() && store.useStep(member.id(), step.getAsLong());
        }
        if (code.matches("[A-Z0-9]{8}") && store.useRecoveryCode(member.id(), TokenHasher.sha256(code), now)) {
            log.warn("Backoffice: código de recuperação usado por {}", member.id());
            store.audit(member.id(), member.email(), "staff.recovery_code", null, null, now);
            return true;
        }
        return false;
    }

    private static ApiException inviteInvalid() {
        return ApiException.unprocessable("staff_invite_invalid",
                "Convite inválido ou vencido. Peça um novo a um administrador.");
    }

    public record Session(String accessToken, String refreshToken) {
    }

    /** {@code secret}: para digitar no app; {@code uri}: para o QR code. */
    public record InviteView(String email, String name, String role, String secret, String uri) {
    }
}
