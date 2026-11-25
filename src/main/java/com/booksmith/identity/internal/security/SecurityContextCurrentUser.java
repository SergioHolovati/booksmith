package com.booksmith.identity.internal.security;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.AuthenticatedUser;
import com.booksmith.identity.api.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
class SecurityContextCurrentUser implements CurrentUser {

    @Override
    public java.util.Optional<AuthenticatedUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? java.util.Optional.of(user) : java.util.Optional.empty();
    }

    @Override
    public AuthenticatedUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        throw ApiException.unauthorized("Autenticação necessária.");
    }
}
