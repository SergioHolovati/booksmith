package com.booksmith.monitoring.web;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.CurrentUser;
import com.booksmith.identity.api.UserDirectory;
import com.booksmith.monitoring.internal.TrafficReport;
import com.booksmith.monitoring.internal.TrafficStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Backoffice: uso da API e sinais de ataque nas últimas horas. Só administradores (403 {@code admin_only}). */
@RestController
@RequestMapping("/api/v1/admin/monitoring")
@RequiredArgsConstructor
class MonitoringAdminController {

    private final TrafficStore store;
    private final UserDirectory users;
    private final CurrentUser currentUser;

    /** A conta mais ativa, com o e-mail para o admin reconhecer. */
    record UserView(UUID id, String email, long requests) {
    }

    record ReportView(int hours, TrafficReport.Totals totals, List<TrafficReport.HourPoint> series,
                      List<TrafficReport.RouteStat> routes, List<TrafficReport.IpStat> addresses, List<UserView> users,
                      List<TrafficReport.Ranked> probes, Map<String, Long> incidents) {
    }

    @GetMapping
    ReportView report(@RequestParam(defaultValue = "24") int hours) {
        if (!users.isAdmin(currentUser.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "admin_only", "Só a equipe do Booksmith vê o monitoramento.",
                    Map.of());
        }
        TrafficReport report = store.report(hours);
        List<UserView> active = report.users().stream()
                .map(r -> {
                    UUID id = parse(r.name());
                    return new UserView(id, id == null ? null : users.email(id).orElse(null), r.count());
                })
                .toList();
        return new ReportView(report.hours(), report.totals(), report.series(), report.routes(), report.addresses(),
                active, report.probes(), report.incidents());
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
