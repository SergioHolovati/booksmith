package com.booksmith.notification.internal;

import com.booksmith.notification.api.EmailSender;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
class ResendEmailSender implements EmailSender {

    private static final String PASSWORD_RECOVERY_TEMPLATE = "email-templates/password-recovery.html";
    private static final String EMAIL_VERIFICATION_TEMPLATE = "email-templates/email-verification.html";
    private static final String SECURITY_NOTICE_TEMPLATE = "email-templates/security-notice.html";
    private static final String MFA_CODE_TEMPLATE = "email-templates/mfa-code.html";

    private final MailProperties properties;

    @Override
    public void sendEmailVerification(String to, String code) {
        send(to, "Confirme seu e-mail no Booksmith",
                loadTemplate(EMAIL_VERIFICATION_TEMPLATE, Map.of("token", code)));
    }

    @Override
    public void sendPasswordReset(String to, String resetLink) {
        send(to, "Redefinição de senha do Booksmith",
                loadTemplate(PASSWORD_RECOVERY_TEMPLATE, Map.of("resetLink", resetLink)));
    }

    @Override
    public void sendMfaCode(String to, String code) {
        send(to, "Seu código de acesso ao Booksmith", loadTemplate(MFA_CODE_TEMPLATE, Map.of("code", escape(code))));
    }

    @Override
    public void sendSecurityNotice(String to, String title, String message) {
        send(to, title + " | Booksmith",
                loadTemplate(SECURITY_NOTICE_TEMPLATE, Map.of("title", escape(title), "message", escape(message))));
    }

    /** O texto entra no HTML: o aparelho vem do User-Agent, que quem acessa escolhe. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void send(String to, String subject, String html) {
        if (!properties.enabled()) {
            // Só em desenvolvimento: permite testar os fluxos sem provedor de e-mail
            log.info("E-mail desativado (booksmith.mail.enabled=false). Para: {} | Assunto: {}\n{}", to, subject, html);
            return;
        }

        var request = CreateEmailOptions.builder()
                .from(properties.from())
                .to(to)
                .subject(subject)
                .html(html)
                .build();
        try {
            new Resend(properties.resendApiKey()).emails().send(request);
        } catch (ResendException e) {
            throw new IllegalStateException("Falha ao enviar e-mail", e);
        }
    }

    private static String loadTemplate(String templatePath, Map<String, String> values) {
        try {
            String html = new ClassPathResource(templatePath).getContentAsString(StandardCharsets.UTF_8);
            for (var entry : values.entrySet()) {
                html = html.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            return html;
        } catch (IOException e) {
            throw new UncheckedIOException("Template de e-mail não encontrado: " + templatePath, e);
        }
    }
}
