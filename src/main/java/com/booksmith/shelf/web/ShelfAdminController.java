package com.booksmith.shelf.web;

import com.booksmith.identity.api.AuthenticatedStaff;
import com.booksmith.identity.api.CurrentStaff;
import com.booksmith.identity.api.StaffAudit;
import com.booksmith.identity.api.StaffRole;
import com.booksmith.shelf.internal.ShelfModeration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Backoffice: denúncias da Estante (moderadores e administradores). Toda decisão fica na auditoria. */
@RestController
@RequestMapping("/api/v1/admin/shelf")
@RequiredArgsConstructor
class ShelfAdminController {

    private final ShelfModeration moderation;
    private final CurrentStaff currentStaff;
    private final StaffAudit audit;

    @GetMapping("/reports")
    List<ShelfModeration.ReportedTarget> reports() {
        currentStaff.require(StaffRole.MODERATOR);
        return moderation.open();
    }

    record RemoveRequest(@Size(max = 300) String reason) {
    }

    @PostMapping("/reports/{targetType}/{targetId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable String targetType, @PathVariable UUID targetId,
                @RequestBody(required = false) @Valid RemoveRequest request) {
        AuthenticatedStaff staff = currentStaff.require(StaffRole.MODERATOR);
        String reason = request == null ? null : request.reason();
        moderation.remove(targetType, targetId, reason, staff.id());
        audit.record("shelf.remove", targetType + ":" + targetId, reason);
    }

    @PostMapping("/reports/{targetType}/{targetId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void dismiss(@PathVariable String targetType, @PathVariable UUID targetId) {
        AuthenticatedStaff staff = currentStaff.require(StaffRole.MODERATOR);
        moderation.dismiss(targetType, targetId, staff.id());
        audit.record("shelf.dismiss", targetType + ":" + targetId, null);
    }

    /** Devolver uma obra tirada do ar: só administrador. */
    @PostMapping("/works/{workId}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void restore(@PathVariable UUID workId) {
        currentStaff.require(StaffRole.ADMIN);
        moderation.restore(workId);
        audit.record("shelf.restore", workId.toString(), null);
    }
}
