package com.booksmith.app.service.impl;

import com.booksmith.app.service.EmailService;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class EmailServiceImpl implements EmailService {

    private static final String PASSWORD_RECOVERY_TEMPLATE = "email-templates/password-recovery.html";
    private static final String EMAIL_VERIFICATION_TEMPLATE = "email-templates/email-verification.html";

    @Value("${resend.api-key}")
    private String apiKey;

    @Value("${resend.from-email}")
    private String fromEmail;

    public void sendPasswordRecoveryEmail(
            String destinationEmail,
            String token
    ) throws ResendException {

        String resetLink =
                "http://localhost:3000/reset-password?token=" + token;

        String html = loadTemplate(
                PASSWORD_RECOVERY_TEMPLATE,
                Map.of("resetLink", resetLink)
        );

        var request = CreateEmailOptions.builder()
                .from(fromEmail)
                .to(destinationEmail)
                .subject("Reset password")
                .html(html)
                .build();

        Resend resend = new Resend(apiKey);
        //resend.emails().send(request);
    }

    public void sendEmailVerificationEmail(
            String destinationEmail,
            String token
    ) throws ResendException {

        String html = loadTemplate(
                EMAIL_VERIFICATION_TEMPLATE,
                Map.of("token", token)
        );

        var request = CreateEmailOptions.builder()
                .from(fromEmail)
                .to(destinationEmail)
                .subject("Verificacao de email")
                .html(html)
                .build();

        Resend resend = new Resend(apiKey);
        //resend.emails().send(request);
    }

    private String loadTemplate(String templatePath, Map<String, String> values) {
        try {
            String html = new ClassPathResource(templatePath)
                    .getContentAsString(StandardCharsets.UTF_8);

            for (var entry : values.entrySet()) {
                html = html.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }

            return html;
        } catch (IOException e) {
            throw new RuntimeException("Could not load email template: " + templatePath, e);
        }
    }
}
