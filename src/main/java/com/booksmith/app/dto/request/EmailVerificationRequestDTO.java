package com.booksmith.app.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmailVerificationRequestDTO {

    @NotBlank(message = "Email is required.")
    @Email(message = "Invalid email format.")
    private String email;

    @NotBlank(message = "Token is required.")
    @Pattern(regexp = "[A-Z0-9]{6}", message = "Token must contain 6 uppercase alphanumeric characters.")
    private String token;
}
