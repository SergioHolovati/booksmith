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
import java.util.UUID;

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
    private final com.booksmith.identity.internal.invite.InviteService invites;

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
        // "sub": a identidade da conta Google, que não muda (o e-mail pode mudar)
        String subject = oauthUser.getName();
        String linkTicket = GoogleLinkIntentFilter.ticket(request);
        String invite = GoogleLinkIntentFilter.invite(request);

        endHttpSession(request);

        // Só vincula contas quando o Google garante que o e-mail pertence ao usuário
        if (email == null || !isEmailVerified(oauthUser)) {
            response.sendRedirect(properties.frontendUrl()
                    + (linkTicket == null ? "/login?error=google_email_not_verified" : "/profile?google=email_not_verified"));
            return;
        }

        if (linkTicket != null) {
            response.sendRedirect(properties.frontendUrl() + "/profile?google=" + link(linkTicket, subject, email));
            return;
        }

        User user = userRepository.findByGoogleSubject(subject).orElse(null);
        if (user == null) {
            User byEmail = userRepository.findByEmailIgnoreCase(email).orElse(null);
            if (byEmail != null && !alreadyGoogle(byEmail)) {
                // Conta com senha nunca ligada ao Google: a ligação é pedida no perfil, depois de entrar com a senha
                response.sendRedirect(properties.frontendUrl() + "/login?error=google_account_exists");
                return;
            }
            if (byEmail != null) {
                user = attachSubject(byEmail, subject, email);
            } else {
                UUID inviteId;
                try {
                    // Beta fechado: conta nova pelo Google também gasta um convite
                    inviteId = invites.redeem(invite);
                } catch (com.booksmith.common.error.ApiException e) {
                    response.sendRedirect(properties.frontendUrl() + "/login?error=google_invite_required");
                    return;
                }
                user = createGoogleUser(email, oauthUser.getAttribute("name"), subject);
                user.setInviteCodeId(inviteId);
            }
        }
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

    /**
     * "Vincular ao Google" pedido no perfil: liga esta conta Google à conta do tíquete.
     *
     * @return o resultado que o perfil mostra: {@code linked}, {@code expired} (tíquete vencido ou usado) ou
     *         {@code in_use} (a conta Google já entra em outra conta do Booksmith)
     */
    private String link(String ticket, String subject, String email) {
        User user = loginCodes.consumeLinkTicket(ticket).flatMap(userRepository::findById).orElse(null);
        if (user == null) {
            return "expired";
        }
        boolean otherBySubject = userRepository.findByGoogleSubject(subject)
                .filter(other -> !other.getId().equals(user.getId())).isPresent();
        boolean otherByEmail = userRepository.findByEmailIgnoreCase(email)
                .filter(other -> !other.getId().equals(user.getId())).isPresent();
        if (otherBySubject || otherByEmail) {
            return "in_use";
        }
        boolean already = subject.equals(user.getGoogleSubject());
        user.setGoogleLinked(true);
        user.setGoogleSubject(subject);
        user.setGoogleEmail(email);
        if (!already) {
            emailSender.sendSecurityNotice(user.getEmail(), "Sua conta foi ligada ao Google",
                    "Agora também dá para entrar no Booksmith com a conta Google " + email + ".");
        }
        return "linked";
    }

    /** Conta criada pelo Google, ou ligada a ele antes de guardarmos a identidade da conta Google (V28). */
    private static boolean alreadyGoogle(User user) {
        return user.getPasswordHash() == null || user.isGoogleLinked();
    }

    /** Guarda a identidade da conta Google numa conta que já era do Google (anterior à V28). */
    private static User attachSubject(User user, String subject, String email) {
        if (user.getGoogleSubject() == null) {
            user.setGoogleSubject(subject);
            user.setGoogleEmail(email);
        }
        user.setGoogleLinked(true);
        user.setEmailVerified(true);
        user.setEmailVerificationCodeHash(null);
        user.setEmailVerificationExpiresAt(null);
        return user;
    }

    private User createGoogleUser(String email, String name, String subject) {
        String[] names = name == null || name.isBlank() ? new String[0] : name.trim().split("\\s+", 2);
        return userRepository.save(User.builder()
                .email(email)
                .firstName(names.length > 0 ? names[0] : null)
                .lastName(names.length > 1 ? names[1] : null)
                .username(Usernames.suggest(name, userRepository::existsByUsernameIgnoreCase))
                .provider(AuthProvider.GOOGLE)
                .googleLinked(true)
                .googleSubject(subject)
                .googleEmail(email)
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
