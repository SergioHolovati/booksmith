package com.booksmith.identity.internal.mfa;

import com.booksmith.common.crypto.SecretBox;
import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.user.TokenHasher;
import com.booksmith.identity.internal.user.User;
import com.booksmith.identity.internal.user.UserRepository;
import com.booksmith.notification.api.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MfaServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T23:00:00Z");

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, Map<Object, Object>> hashes = new HashMap<>();
    private final List<MfaFactor> factorRows = new ArrayList<>();
    private final List<MfaRecoveryCode> codeRows = new ArrayList<>();
    private final EmailSender emailSender = mock(EmailSender.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private final SecretBox box = new SecretBox("", "test-secret-with-at-least-32-characters!!");
    private final User lia = User.builder().id(UUID.randomUUID()).email("lia@example.com")
            .passwordHash(passwords.encode("Senha@123")).build();
    private MfaService mfa;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.opsForHash()).thenReturn(hashOps);
        doAnswer(i -> values.put(i.getArgument(0), i.getArgument(1))).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(i -> values.get(i.<String>getArgument(0)));
        when(redis.hasKey(anyString())).thenAnswer(i -> values.containsKey(i.<String>getArgument(0))
                || hashes.containsKey(i.<String>getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(i -> values.remove(i.<String>getArgument(0)) != null
                | hashes.remove(i.<String>getArgument(0)) != null);
        doAnswer(i -> {
            hashes.computeIfAbsent(i.getArgument(0), k -> new HashMap<>()).putAll(i.getArgument(1));
            return null;
        }).when(hashOps).putAll(anyString(), anyMap());
        when(hashOps.get(anyString(), any())).thenAnswer(i -> hashes.getOrDefault(i.<String>getArgument(0), Map.of())
                .get(i.getArgument(1)));
        when(hashOps.increment(anyString(), any(), anyLong())).thenAnswer(i -> {
            Map<Object, Object> hash = hashes.computeIfAbsent(i.getArgument(0), k -> new HashMap<>());
            long next = Long.parseLong(String.valueOf(hash.getOrDefault(i.getArgument(1), "0"))) + i.<Long>getArgument(2);
            hash.put(i.getArgument(1), String.valueOf(next));
            return next;
        });

        MfaFactorRepository factors = mock(MfaFactorRepository.class);
        when(factors.save(any())).thenAnswer(i -> {
            MfaFactor factor = i.getArgument(0);
            if (!factorRows.contains(factor)) {
                factorRows.add(factor);
            }
            return factor;
        });
        when(factors.findAllByUserId(any())).thenAnswer(i -> List.copyOf(factorRows));
        when(factors.findByUserIdAndType(any(), anyString())).thenAnswer(i -> factorRows.stream()
                .filter(f -> f.getType().equals(i.getArgument(1))).findFirst());
        when(factors.existsByUserIdAndConfirmedAtIsNotNull(any())).thenAnswer(i -> factorRows.stream().anyMatch(MfaFactor::confirmed));
        doAnswer(i -> {
            factorRows.clear();
            return null;
        }).when(factors).deleteAllByUserId(any());

        MfaRecoveryCodeRepository codes = mock(MfaRecoveryCodeRepository.class);
        when(codes.save(any())).thenAnswer(i -> {
            codeRows.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(codes.findByUserIdAndCodeHashAndUsedAtIsNull(any(), anyString())).thenAnswer(i -> codeRows.stream()
                .filter(c -> c.getUsedAt() == null && c.getCodeHash().equals(i.getArgument(1))).findFirst());
        when(codes.countByUserIdAndUsedAtIsNull(any())).thenAnswer(i -> codeRows.stream().filter(c -> c.getUsedAt() == null).count());
        doAnswer(i -> {
            codeRows.clear();
            return null;
        }).when(codes).deleteAllByUserId(any());

        UserRepository users = mock(UserRepository.class);
        when(users.findById(lia.getId())).thenReturn(Optional.of(lia));
        mfa = new MfaService(factors, codes, users, redis, box, emailSender, passwords, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String totpNow(String secret, long offsetSteps) {
        return Totp.code(Totp.unbase32(secret), Totp.step(NOW) + offsetSteps);
    }

    @Test
    void authenticatorAppIsOnOnlyAfterACodeAndGivesTenRecoveryCodes() {
        var setup = mfa.startTotp(lia.getId());
        assertThat(setup.uri()).startsWith("otpauth://totp/Booksmith%3Alia%40example.com?secret=" + setup.secret());
        // O segredo vai criptografado para o banco
        assertThat(factorRows.getFirst().getSecret()).doesNotContain(setup.secret());
        assertThat(mfa.enabled(lia.getId())).isFalse();

        assertThatThrownBy(() -> mfa.confirm(lia.getId(), "totp", "000000"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("mfa_code_invalid"));
        List<String> recovery = mfa.confirm(lia.getId(), "totp", totpNow(setup.secret(), 0));

        assertThat(mfa.enabled(lia.getId())).isTrue();
        assertThat(recovery).hasSize(10).allMatch(c -> c.matches("[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}"));
        assertThat(codeRows).extracting(MfaRecoveryCode::getCodeHash)
                .contains(TokenHasher.sha256(recovery.getFirst().replace("-", "")));
        assertThat(mfa.status(lia.getId())).isEqualTo(new MfaService.Status(true, List.of("totp"), 10));
        verify(emailSender).sendSecurityNotice(eq("lia@example.com"), eq("Verificação em duas etapas ativada"), anyString());
    }

    @Test
    void loginChallengeAcceptsTheAppCodeOnceAndRecoveryCodesOnce() {
        var setup = mfa.startTotp(lia.getId());
        List<String> recovery = mfa.confirm(lia.getId(), "totp", totpNow(setup.secret(), -1));

        var challenge = mfa.challenge(lia.getId());
        assertThat(challenge.methods()).containsExactly("totp");
        // O mesmo código (mesmo passo) não vale de novo; o do passo seguinte vale
        assertThatThrownBy(() -> mfa.verifyChallenge(challenge.id(), totpNow(setup.secret(), -1)))
                .isInstanceOf(ApiException.class);
        assertThat(mfa.verifyChallenge(challenge.id(), totpNow(setup.secret(), 0).substring(0, 3) + " "
                + totpNow(setup.secret(), 0).substring(3))).isEqualTo(lia.getId());
        // O desafio acaba no acerto
        assertThatThrownBy(() -> mfa.verifyChallenge(challenge.id(), "123456"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("mfa_challenge_expired"));

        var second = mfa.challenge(lia.getId());
        assertThat(mfa.verifyChallenge(second.id(), recovery.get(3).toLowerCase())).isEqualTo(lia.getId());
        var third = mfa.challenge(lia.getId());
        assertThatThrownBy(() -> mfa.verifyChallenge(third.id(), recovery.get(3))).isInstanceOf(ApiException.class);
        assertThat(mfa.status(lia.getId()).recoveryCodesLeft()).isEqualTo(9);
        verify(emailSender).sendSecurityNotice(eq("lia@example.com"), eq("Código de recuperação usado"), anyString());
    }

    @Test
    void fiveWrongCodesEndTheChallenge() {
        var setup = mfa.startTotp(lia.getId());
        mfa.confirm(lia.getId(), "totp", totpNow(setup.secret(), 0));
        var challenge = mfa.challenge(lia.getId());

        for (int i = 0; i < MfaService.MAX_ATTEMPTS - 1; i++) {
            assertThatThrownBy(() -> mfa.verifyChallenge(challenge.id(), "000000"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("mfa_code_invalid"));
        }
        assertThatThrownBy(() -> mfa.verifyChallenge(challenge.id(), "000000"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("mfa_challenge_expired"));
        assertThatThrownBy(() -> mfa.verifyChallenge(challenge.id(), totpNow(setup.secret(), 1)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("mfa_challenge_expired"));
    }

    @Test
    void emailCodeWorksOnceAndWaitsAMinuteToResend() {
        mfa.startEmail(lia.getId());
        ArgumentCaptor<String> setupCode = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendMfaCode(eq("lia@example.com"), setupCode.capture());
        mfa.confirm(lia.getId(), "email", setupCode.getValue());

        var challenge = mfa.challenge(lia.getId());
        mfa.sendChallengeEmail(challenge.id());
        assertThatThrownBy(() -> mfa.sendChallengeEmail(challenge.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("mfa_code_wait"));
        ArgumentCaptor<String> loginCode = ArgumentCaptor.forClass(String.class);
        verify(emailSender, org.mockito.Mockito.times(2)).sendMfaCode(eq("lia@example.com"), loginCode.capture());
        assertThat(mfa.verifyChallenge(challenge.id(), loginCode.getValue())).isEqualTo(lia.getId());
    }

    @Test
    void disablingAsksForThePasswordAndACode() {
        var setup = mfa.startTotp(lia.getId());
        mfa.confirm(lia.getId(), "totp", totpNow(setup.secret(), -1));

        assertThatThrownBy(() -> mfa.disable(lia.getId(), "errada", totpNow(setup.secret(), 0)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("password_invalid"));
        assertThatThrownBy(() -> mfa.disable(lia.getId(), "Senha@123", "000000"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("mfa_code_invalid"));
        mfa.disable(lia.getId(), "Senha@123", totpNow(setup.secret(), 0));

        assertThat(mfa.enabled(lia.getId())).isFalse();
        assertThat(codeRows).isEmpty();
        verify(emailSender).sendSecurityNotice(eq("lia@example.com"), eq("Verificação em duas etapas desativada"), anyString());
    }
}
