package com.booksmith.identity.web;

import com.booksmith.identity.internal.auth.AuthService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

final class AuthDtos {

    static final String PASSWORD_PATTERN = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!])(?=\\S+$).{8,}$";
    static final String PASSWORD_MESSAGE =
            "A senha precisa ter 8+ caracteres, com maiúscula, minúscula, número e caractere especial (@#$%^&+=!).";

    private AuthDtos() {
    }

    record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    record RegisterRequest(
            @NotBlank(message = "Informe o nome.") @Size(min = 2, max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Size(max = 40) @Pattern(regexp = "^[a-zA-Z0-9._-]*$", message = "Use letras, números, ponto, hífen ou _.")
            String username,
            @NotBlank(message = "Informe o e-mail.") @Email(message = "E-mail inválido.") String email,
            @NotBlank @Pattern(regexp = PASSWORD_PATTERN, message = PASSWORD_MESSAGE) String password,
            @NotNull(message = "Informe a data de nascimento.") @Past LocalDate birthDate) {

        AuthService.Registration toRegistration() {
            String effectiveUsername = username == null || username.isBlank()
                    ? email.substring(0, email.indexOf('@'))
                    : username;
            return new AuthService.Registration(firstName.trim(), lastName == null ? null : lastName.trim(),
                    effectiveUsername, email, password, birthDate);
        }
    }

    record VerifyEmailRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9]{6}", message = "O código tem 6 caracteres.") String code) {
    }

    record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Pattern(regexp = PASSWORD_PATTERN, message = PASSWORD_MESSAGE) String password) {
    }

    record SessionResponse(String accessToken, String tokenType, long expiresIn, UserSummary user) {

        static SessionResponse from(AuthService.Session session) {
            var user = session.user();
            return new SessionResponse(
                    session.token().value(),
                    "Bearer",
                    session.token().expiresIn().toSeconds(),
                    new UserSummary(user.getId(), user.getUsername(), user.displayName(), user.isEmailVerified()));
        }
    }

    record UserSummary(UUID id, String username, String displayName, boolean emailVerified) {
    }
}
