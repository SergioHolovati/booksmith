package com.booksmith.identity.web;

import com.booksmith.identity.api.AuthenticatedUser;
import com.booksmith.identity.api.CurrentUser;
import com.booksmith.identity.internal.auth.AuthService;
import com.booksmith.identity.internal.auth.AuthService.DeviceSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Aparelhos conectados à conta (§20.6): ver e desconectar, por exemplo depois de perder o celular. */
@RestController
@RequestMapping("/api/v1/me/sessions")
@RequiredArgsConstructor
class SessionController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    @GetMapping
    List<DeviceSession> list() {
        AuthenticatedUser user = currentUser.get();
        return authService.sessions(user.id(), user.sessionId());
    }

    /** Encerra todas as sessões menos a atual. */
    @DeleteMapping
    EndedSessions endOthers() {
        AuthenticatedUser user = currentUser.get();
        return new EndedSessions(authService.endOtherSessions(user.id(), user.sessionId()));
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void end(@PathVariable String sessionId) {
        authService.endSession(currentUser.id(), sessionId);
    }

    record EndedSessions(int ended) {
    }
}
