package com.booksmith.identity.internal.terms;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Termos de uso, termo de responsabilidade e política de privacidade: um só aceite, com a versão em vigor
 * ({@code booksmith.terms.version}, ex.: {@code 2026-11-13}). Criar a conta exige aceitar; quando a versão muda, a
 * conta volta a ficar pendente (o JWT sai com {@code pending=terms}) até aceitar a nova.
 */
@Slf4j
@Service
public class TermsService {

    private final TermsAcceptanceRepository acceptances;
    private final Clock clock;
    private final String version;

    TermsService(TermsAcceptanceRepository acceptances, Clock clock,
                 @Value("${booksmith.terms.version:2026-11-13}") String version) {
        this.acceptances = acceptances;
        this.clock = clock;
        this.version = version;
    }

    /** A versão em vigor (a que o app mostra nas páginas e manda no aceite). */
    public String version() {
        return version;
    }

    /** Se a conta ainda não aceitou a versão em vigor. */
    public boolean pending(User user) {
        return !version.equals(user.getTermsVersion());
    }

    /**
     * Cadastro ou "completar cadastro": sem o aceite, não cria a conta.
     *
     * @throws ApiException 422 {@code terms_required}
     */
    public void requireAccepted(Boolean accepted) {
        if (!Boolean.TRUE.equals(accepted)) {
            throw ApiException.unprocessable("terms_required",
                    "Para criar a conta, é preciso aceitar os termos de uso e a política de privacidade.");
        }
    }

    /** Guarda o aceite da versão em vigor no usuário e no histórico. */
    @Transactional
    public void accept(User user) {
        user.setTermsVersion(version);
        user.setTermsAcceptedAt(Instant.now(clock));
        acceptances.save(TermsAcceptance.builder().userId(user.getId()).version(version).build());
        log.info("Termos {} aceitos por userId: {}", version, user.getId());
    }
}
