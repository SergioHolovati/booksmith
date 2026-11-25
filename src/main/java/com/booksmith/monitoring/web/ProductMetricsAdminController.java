package com.booksmith.monitoring.web;

import com.booksmith.identity.api.CurrentStaff;
import com.booksmith.identity.api.StaffRole;
import com.booksmith.monitoring.internal.ProductMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backoffice: métricas de produto (retenção, ativação, uso de cada parte, agente de continuidade, IA e planos).
 * Só administradores: são números do negócio.
 */
@RestController
@RequestMapping("/api/v1/admin/metrics")
@RequiredArgsConstructor
class ProductMetricsAdminController {

    private final ProductMetrics metrics;
    private final CurrentStaff currentStaff;

    @GetMapping
    ProductMetrics.Report report() {
        currentStaff.require(StaffRole.ADMIN);
        return metrics.report();
    }
}
