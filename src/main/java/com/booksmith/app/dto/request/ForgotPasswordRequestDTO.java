package com.booksmith.app.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class ForgotPasswordRequestDTO {

    @NotBlank(message = "Email is required.")
    @Email(message = "Invalid email format.")
    String email;
}
