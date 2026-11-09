/**
 * Envio de notificações. Hoje só e-mails transacionais (Resend); SES e notificações no app virão depois (§21).
 */
@ApplicationModule(displayName = "Notificações", allowedDependencies = "common")
package com.booksmith.notification;

import org.springframework.modulith.ApplicationModule;
