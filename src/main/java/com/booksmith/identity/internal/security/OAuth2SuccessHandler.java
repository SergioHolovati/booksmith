package com.booksmith.identity.internal.security;

import com.booksmith.identity.internal.user.AuthProvider;
import com.booksmith.identity.internal.user.User;
import com.booksmith.identity.internal.user.UserRepository;
import com.booksmith.identity.internal.user.Usernames;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Conclui o login com Google: cria ou vincula o usuário e devolve ao frontend um <b>código de uso único</b> no
 * fragmento da URL ({@code #code=}), que não é enviado a servidores nem fica em logs de acesso. O app troca o
 * código pela sessão em {@code POST /api/v1/auth/google/exchange}; o código vale 60 segundos e uma vez só, então o
 * que sobra no histórico do navegador não abre a conta.
 *
 * <p>Conta criada pelo Google não tem data de nascimento: o token sai com o cadastro pendente e o app pede a data
 * (e confere a idade mínima) antes de liberar o resto.
 */
@Component
@RequiredArgsConstructor
class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final OneTimeLoginCodes loginCodes;
    private final SecurityProperties properties;
    private final com.booksmith.notification.api.EmailSender emailSender;

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        // Hoje só o Google entra por aqui; outro provedor (ou nenhum) volta para o login, em vez de erro 500
        if (!(authentication.getPrincipal() instanceof OAuth2User oauthUser)) {
            response.sendRedirect(properties.frontendUrl() + "/login?error=google");
            return;
        }
        String email = oauthUser.getAttribute("email");

        endHttpSession(request);

        // Só vincula contas quando o Google garante que o e-mail pertence ao usuário
        if (email == null || !isEmailVerified(oauthUser)) {
            response.sendRedirect(properties.frontendUrl() + "/login?error=google_email_not_verified");
            return;
        }

        User user = userRepository.findByEmailIgnoreCase(email)
                .map(this::linkGoogle)
                .orElseGet(() -> createGoogleUser(email, oauthUser.getAttribute("name")));
        user.setLastLoginAt(Instant.now());

        String code = loginCodes.create(user.getId());
        response.sendRedirect(properties.frontendUrl() + "/auth/callback#code="
                + URLEncoder.encode(code, StandardCharsets.UTF_8));
    }

    private static boolean isEmailVerified(OAuth2User user) {
        Object verified = user.getAttribute("email_verified");
        if (verified == null) {
            verified = user.getAttribute("verified_email");
        }
        return Boolean.parseBoolean(String.valueOf(verified));
    }

    /** Conta com senha que passa a entrar também pelo Google: o dono fica sabendo. */
    private User linkGoogle(User user) {
        if (!user.isGoogleLinked()) {
            emailSender.sendSecurityNotice(user.getEmail(), "Sua conta foi ligada ao Google",
                    "Agora também dá para entrar no Booksmith com a conta Google deste e-mail.");
        }
        user.setGoogleLinked(true);
        user.setEmailVerified(true);
        user.setEmailVerificationCodeHash(null);
        user.setEmailVerificationExpiresAt(null);
        return user;
    }

    private User createGoogleUser(String email, String name) {
        String[] names = name == null || name.isBlank() ? new String[0] : name.trim().split("\\s+", 2);
        return userRepository.save(User.builder()
                .email(email)
                .firstName(names.length > 0 ? names[0] : null)
                .lastName(names.length > 1 ? names[1] : null)
                .username(Usernames.suggest(name, userRepository::existsByUsernameIgnoreCase))
                .provider(AuthProvider.GOOGLE)
                .googleLinked(true)
                .emailVerified(true)
                .build());
    }

    private static void endHttpSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
