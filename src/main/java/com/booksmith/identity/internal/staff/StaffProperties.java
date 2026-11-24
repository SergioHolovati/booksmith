package com.booksmith.identity.internal.staff;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Contas da equipe.
 *
 * @param adminUrl       endereço do backoffice (o link do convite)
 * @param bootstrapEmail se a equipe estiver vazia ao subir, quem recebe o convite de administrador (a primeira conta)
 * @param emailDomains   os domínios de e-mail aceitos na equipe (ex.: booksmith.com.br); nenhum outro entra nem loga
 */
@ConfigurationProperties("booksmith.staff")
public record StaffProperties(String adminUrl, String bootstrapEmail, List<String> emailDomains) {

    public StaffProperties {
        emailDomains = emailDomains == null ? List.of() : emailDomains.stream()
                .map(d -> d.strip().toLowerCase(Locale.ROOT)).filter(d -> !d.isEmpty()).toList();
    }

    /** O e-mail é de um domínio da empresa (sem domínio configurado, nenhum é). */
    public boolean allowed(String email) {
        if (email == null) {
            return false;
        }
        String clean = email.strip().toLowerCase(Locale.ROOT);
        int at = clean.lastIndexOf('@');
        return at > 0 && emailDomains.contains(clean.substring(at + 1));
    }
}
