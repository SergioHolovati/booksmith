package com.booksmith.identity.internal.staff;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.AuthenticatedStaff;
import com.booksmith.identity.api.CurrentStaff;
import com.booksmith.identity.api.StaffAudit;
import com.booksmith.identity.api.StaffRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A autenticação das rotas da equipe: o filtro que lê o token do backoffice (só ele: o de cliente não é aceito), o
 * {@link CurrentStaff} e o {@link StaffAudit}.
 */
@Component
public class StaffSecurity implements CurrentStaff, StaffAudit {

    private static final String BEARER = "Bearer ";

    private final StaffTokens tokens;
    private final StaffStore store;
    private final Clock clock;

    StaffSecurity(StaffTokens tokens, StaffStore store, Clock clock) {
        this.tokens = tokens;
        this.store = store;
        this.clock = clock;
    }

    /** Filtro da cadeia da equipe (não é um @Component: só entra na cadeia do Spring Security). */
    public OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String header = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (header != null && header.startsWith(BEARER)) {
                    tokens.verify(header.substring(BEARER.length())).ifPresent(staff ->
                            SecurityContextHolder.getContext().setAuthentication(
                                    UsernamePasswordAuthenticationToken.authenticated(staff, null, List.of())));
                }
                chain.doFilter(request, response);
            }
        };
    }

    @Override
    public AuthenticatedStaff get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedStaff staff) {
            return staff;
        }
        throw ApiException.unauthorized("Entre no backoffice.");
    }

    @Override
    public AuthenticatedStaff require(StaffRole... roles) {
        AuthenticatedStaff staff = get();
        if (staff.role() == StaffRole.ADMIN || Arrays.asList(roles).contains(staff.role())) {
            return staff;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "staff_role", "O seu papel na equipe não dá acesso a isto.",
                Map.of());
    }

    @Override
    public void record(String action, String target, String detail) {
        AuthenticatedStaff staff = get();
        store.audit(staff.id(), staff.email(), action, target, detail, Instant.now(clock));
    }

    /** O registro de ações, do mais recente ao mais antigo (só administradores leem). */
    public List<AuditEntry> audit(int limit) {
        return store.audit(limit);
    }
}
