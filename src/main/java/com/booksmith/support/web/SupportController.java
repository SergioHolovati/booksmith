package com.booksmith.support.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.support.internal.SupportContext;
import com.booksmith.support.internal.SupportRequest;
import com.booksmith.support.internal.SupportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Pedidos de suporte (§20.6): "Ajuda → Falar com o suporte" e a lista "Meus pedidos". */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class SupportController {

    private final SupportService supportService;
    private final CurrentUser currentUser;

    @PostMapping("/support/requests")
    @ResponseStatus(HttpStatus.CREATED)
    RequestResponse create(@RequestBody CreateRequest request) {
        return response(supportService.create(currentUser.get(), request.category(), request.message(),
                request.context()));
    }

    @GetMapping("/me/support/requests")
    List<RequestResponse> mine() {
        return supportService.mine(currentUser.id()).stream().map(SupportController::response).toList();
    }

    private static RequestResponse response(SupportRequest r) {
        return new RequestResponse(r.getId(), r.protocol(), r.getCategory(), r.getMessage(), r.getPriority(),
                r.getStatus(), r.getEmail(), r.getCreatedAt());
    }

    record CreateRequest(String category, String message, SupportContext context) {
    }

    /** {@code email}: para onde vai a resposta. */
    record RequestResponse(UUID id, String protocol, String category, String message, String priority, String status,
                           String email, Instant createdAt) {
    }
}
