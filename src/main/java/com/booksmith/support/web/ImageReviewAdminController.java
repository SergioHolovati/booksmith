package com.booksmith.support.web;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.CurrentUser;
import com.booksmith.identity.api.UserDirectory;
import com.booksmith.media.api.ImageReviews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backoffice: as imagens que a moderação separou (para revisar e recusadas). Só administradores (403
 * {@code admin_only}). Remover tira a imagem do armazenamento.
 */
@RestController
@RequestMapping("/api/v1/admin/image-reviews")
@RequiredArgsConstructor
class ImageReviewAdminController {

    private final ImageReviews reviews;
    private final UserDirectory users;
    private final CurrentUser currentUser;

    record ReviewView(UUID id, UUID ownerId, String ownerEmail, String category, String url, double score,
                      String status, String reason, Instant createdAt, Instant decidedAt) {
    }

    @GetMapping
    List<ReviewView> list(@RequestParam(defaultValue = "100") int limit) {
        requireAdmin();
        return reviews.list(limit).stream()
                .map(r -> new ReviewView(r.id(), r.ownerId(), users.email(r.ownerId()).orElse(null), r.category(),
                        r.url(), r.score(), r.status(), r.reason(), r.createdAt(), r.decidedAt()))
                .toList();
    }

    @PostMapping("/{id}/keep")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void keep(@PathVariable UUID id) {
        UUID admin = requireAdmin();
        if (!reviews.keep(id, admin)) {
            throw notPending();
        }
    }

    @PostMapping("/{id}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID id) {
        UUID admin = requireAdmin();
        if (!reviews.remove(id, admin)) {
            throw notPending();
        }
    }

    private UUID requireAdmin() {
        UUID id = currentUser.id();
        if (!users.isAdmin(id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "admin_only", "Só a equipe do Booksmith revisa imagens.",
                    Map.of());
        }
        return id;
    }

    private static ApiException notPending() {
        return new ApiException(HttpStatus.NOT_FOUND, "review_not_pending", "Esta imagem não está esperando revisão.",
                Map.of());
    }
}
