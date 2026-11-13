package com.booksmith.identity.internal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * "Vincular ao Google" no perfil: o app abre {@code /oauth2/authorization/google?link=<tíquete>}. O tíquete fica na
 * sessão HTTP do fluxo OAuth (a mesma que guarda o {@code state}) até o Google voltar; aí o
 * {@link OAuth2SuccessHandler} liga a conta Google à conta do tíquete, em vez de entrar pela conta do e-mail.
 * Sem {@code link}, o pedido é um login comum e um tíquete antigo é descartado.
 */
class GoogleLinkIntentFilter extends OncePerRequestFilter {

    static final String ATTRIBUTE = "booksmith.googleLinkTicket";
    /** Código de convite para criar a conta pelo Google (beta fechado). */
    static final String INVITE_ATTRIBUTE = "booksmith.googleInvite";
    private static final String START = "/oauth2/authorization/google";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !START.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        remember(request, "link", ATTRIBUTE);
        remember(request, "invite", INVITE_ATTRIBUTE);
        chain.doFilter(request, response);
    }

    /** Guarda o parâmetro na sessão do fluxo OAuth; sem ele, tira o de uma tentativa anterior. */
    private static void remember(HttpServletRequest request, String parameter, String attribute) {
        String value = request.getParameter(parameter);
        if (value != null && !value.isBlank() && value.length() <= 200) {
            request.getSession(true).setAttribute(attribute, value);
        } else {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(attribute);
            }
        }
    }

    /** O tíquete guardado no começo do fluxo, ou null num login comum. */
    static String ticket(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(ATTRIBUTE);
    }

    /** O código de convite do "Criar conta com Google", ou null. */
    static String invite(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(INVITE_ATTRIBUTE);
    }
}
