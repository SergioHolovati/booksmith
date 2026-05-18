package com.booksmith.app.dto.request;

import lombok.Getter;

@Getter
public class LoginRequestDTO {
    private String username;
    private String email;
    private String password;
}
