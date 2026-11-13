package com.booksmith.identity.internal.auth;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.mfa.MfaService;
import com.booksmith.identity.internal.security.OneTimeLoginCodes;
import com.booksmith.identity.internal.user.User;
import com.booksmith.identity.internal.user.UserRepository;
import com.booksmith.notification.api.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Ligar e desligar a conta Google pelo perfil. Ligar começa aqui (um tíquete de uso único, 5 minutos) e termina na
 * volta do Google, no {@code OAuth2SuccessHandler}, que liga a conta Google à conta do tíquete.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAccountService {

    private final UserRepository userRepository;
    private final OneTimeLoginCodes loginCodes;
    private final EmailSender emailSender;
    private final MfaService mfaService;

    /**
     * Tíquete para o app abrir {@code /oauth2/authorization/google?link=}. Ligar o Google cria outro jeito de entrar
     * na conta: com MFA ativo, pede um código do app ou do e-mail.
     *
     * @throws ApiException 422 {@code mfa_code_invalid}
     */
    @Transactional
    public String startLink(UUID userId, String mfaCode) {
        mfaService.requireStepUp(userId, mfaCode);
        return loginCodes.createLinkTicket(userId);
    }

    /**
     * Desliga o Google. Só para quem tem senha, senão a pessoa ficaria sem como entrar.
     *
     * @throws ApiException 409 {@code google_only_account} sem senha; 409 {@code google_not_linked}
     */
    @Transactional
    public void unlink(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Usuário não encontrado."));
        if (!user.isGoogleLinked()) {
            throw ApiException.conflict("google_not_linked", "A conta não está ligada ao Google.", Map.of());
        }
        if (user.getPasswordHash() == null) {
            throw ApiException.conflict("google_only_account",
                    "Sua conta só entra pelo Google. Crie uma senha em \"Esqueceu a senha?\" antes de desligar.", Map.of());
        }
        String googleEmail = user.getGoogleEmail();
        user.setGoogleLinked(false);
        user.setGoogleSubject(null);
        user.setGoogleEmail(null);
        log.info("Google desligado pelo usuário userId: {}", userId);
        emailSender.sendSecurityNotice(user.getEmail(), "Google desligado da sua conta",
                googleEmail == null ? "Sua conta do Booksmith não entra mais pelo Google."
                        : "A conta Google " + googleEmail + " não entra mais no seu Booksmith.");
    }
}
