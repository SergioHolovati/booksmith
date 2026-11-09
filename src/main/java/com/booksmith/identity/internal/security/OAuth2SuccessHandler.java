package com.booksmith.identity.internal.security;

import com.booksmith.identity.internal.user.AuthProvider;
import com.booksmith.identity.internal.user.User;
import com.booksmith.identity.internal.user.UserRepository;
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
 * Conclui o login com Google: cria ou vincula o usuário e devolve o token ao frontend no fragmento da URL
 * ({@code #token=}), que não é enviado a servidores nem fica em logs de acesso.
 */
@Component
@RequiredArgsConstructor
class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtTokenProvider tokenProvider;
    private final SecurityProperties properties;

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
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

        String token = tokenProvider.issue(user, request.getHeader("User-Agent")).value();
        response.sendRedirect(properties.frontendUrl() + "/auth/callback#token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    private static boolean isEmailVerified(OAuth2User user) {
        Object verified = user.getAttribute("email_verified");
        if (verified == null) {
            verified = user.getAttribute("verified_email");
        }
        return Boolean.parseBoolean(String.valueOf(verified));
    }

    private User linkGoogle(User user) {
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
                .username(email.substring(0, email.indexOf('@')))
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
