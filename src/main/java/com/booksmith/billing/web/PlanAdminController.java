package com.booksmith.billing.web;

import com.booksmith.billing.api.Plan;
import com.booksmith.billing.internal.PlanService;
import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.AuthenticatedStaff;
import com.booksmith.identity.api.CurrentStaff;
import com.booksmith.identity.api.StaffAudit;
import com.booksmith.identity.api.StaffRole;
import com.booksmith.identity.api.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

/**
 * Backoffice: o plano de uma conta pelo e-mail, e conceder, trocar ou tirar (fundador pago por link, cortesia,
 * compensação). Atendentes e administradores; cada mudança vai para o registro da equipe.
 */
@RestController
@RequestMapping("/api/v1/admin/plans")
@RequiredArgsConstructor
class PlanAdminController {

    private static final Set<String> SOURCES = Set.of("courtesy", "founder");

    private final PlanService plans;
    private final UserDirectory users;
    private final CurrentStaff currentStaff;
    private final StaffAudit audit;
    private final Clock clock;

    /** O plano que vale agora e o concedido (que pode estar vencido). */
    record AccountPlan(UUID userId, String email, String plan, String defaultPlan, PlanService.Subscription granted) {
    }

    /**
     * @param months  quantos meses vale (null ou 0 = sem prazo)
     * @param source  courtesy (cortesia) ou founder (fundador, pago por link)
     */
    record GrantRequest(String email, Plan plan, Integer months, String source, String note) {
    }

    record RevokeRequest(String email) {
    }

    @GetMapping
    AccountPlan get(@RequestParam String email) {
        currentStaff.require(StaffRole.SUPPORT);
        return view(account(email), email);
    }

    @PostMapping
    AccountPlan grant(@RequestBody GrantRequest request) {
        AuthenticatedStaff staff = currentStaff.require(StaffRole.SUPPORT);
        UUID userId = account(request.email());
        if (request.plan() == null) {
            throw ApiException.unprocessable("plan_invalid", "Escolha o plano.");
        }
        String source = request.source() == null ? "courtesy" : request.source();
        if (!SOURCES.contains(source)) {
            throw ApiException.unprocessable("plan_invalid", "Origem inválida (cortesia ou fundador).");
        }
        int months = request.months() == null ? 0 : request.months();
        if (months < 0 || months > 120) {
            throw ApiException.unprocessable("plan_invalid", "De 0 (sem prazo) a 120 meses.");
        }
        Instant expiresAt = months == 0 ? null
                : Instant.now(clock).atOffset(ZoneOffset.UTC).plusMonths(months).toInstant();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (note != null && note.length() > 200) {
            throw ApiException.unprocessable("plan_invalid", "A anotação passou de 200 caracteres.");
        }
        plans.grant(userId, request.plan(), source, "founder".equals(source), expiresAt, note, staff.id());
        audit.record("plan.grant", userId.toString(),
                request.plan().name() + " " + source + (months == 0 ? " sem prazo" : " " + months + " meses"));
        return view(userId, request.email());
    }

    @PostMapping("/revoke")
    AccountPlan revoke(@RequestBody RevokeRequest request) {
        currentStaff.require(StaffRole.ADMIN);
        UUID userId = account(request.email());
        if (plans.revoke(userId)) {
            audit.record("plan.revoke", userId.toString(), null);
        }
        return view(userId, request.email());
    }

    private UUID account(String email) {
        return users.idByEmail(email).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "account_not_found", "Nenhuma conta com esse e-mail."));
    }

    private AccountPlan view(UUID userId, String email) {
        return new AccountPlan(userId, email.strip(), plans.plan(userId).name(), plans.defaultPlan().name(),
                plans.subscription(userId).orElse(null));
    }
}
