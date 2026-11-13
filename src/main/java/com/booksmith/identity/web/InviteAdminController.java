package com.booksmith.identity.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.identity.internal.invite.InviteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Convites do beta fechado: só para administradores (403 {@code admin_only}). */
@RestController
@RequestMapping("/api/v1/admin/invites")
@RequiredArgsConstructor
class InviteAdminController {

    private final InviteService invites;
    private final CurrentUser currentUser;

    @GetMapping
    List<InviteService.InviteView> list() {
        return invites.list(currentUser.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    List<InviteService.InviteView> create(@RequestBody @Valid CreateRequest request) {
        return invites.create(currentUser.id(), request.count() == null ? 1 : request.count(),
                request.maxUses() == null ? 1 : request.maxUses(), request.note(), request.expiresInDays());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id) {
        invites.revoke(currentUser.id(), id);
    }

    /** {@code count}: quantos códigos; {@code maxUses}: quantos cadastros cada um vale. */
    record CreateRequest(Integer count, Integer maxUses, @Size(max = 80) String note, Integer expiresInDays) {
    }
}
