/**
 * Planos e direitos de uso (§17 da arquitetura): o plano de cada conta (Grátis, Escritor, Autor), os limites de cada
 * um e a concessão pelo backoffice (fundadores pagos por link, cortesias). A cobrança automática (Asaas) entra
 * depois. Os outros módulos só leem {@code billing :: api} ({@link com.booksmith.billing.api.Entitlements}).
 */
@ApplicationModule(displayName = "Planos", allowedDependencies = {"common", "identity :: api"})
package com.booksmith.billing;

import org.springframework.modulith.ApplicationModule;
