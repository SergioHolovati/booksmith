package com.booksmith.identity.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.identity.internal.auth.GoogleAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A conta Google ligada à conta do Booksmith (perfil → "Entrar com Google"). */
@RestController
@RequestMapping("/api/v1/me/google")
@RequiredArgsConstructor
class GoogleAccountController {

    private final GoogleAccountService googleAccounts;
    private final CurrentUser currentUser;

    /** Começa a ligação: o app abre {@code /oauth2/authorization/google?link=<ticket>}. */
    @PostMapping("/link")
    LinkTicket startLink() {
        return new LinkTicket(googleAccounts.startLink(currentUser.id()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink() {
        googleAccounts.unlink(currentUser.id());
    }

    record LinkTicket(String ticket) {
    }
}
