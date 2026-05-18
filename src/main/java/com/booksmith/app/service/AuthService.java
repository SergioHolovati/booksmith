package com.booksmith.app.service;

import com.booksmith.app.dto.request.LoginRequestDTO;
import com.booksmith.app.dto.response.LoginResponseDTO;
import com.booksmith.app.dto.request.RegisterRequestDTO;

public interface AuthService {
    LoginResponseDTO login(LoginRequestDTO loginRequestDTO);
    void register(RegisterRequestDTO register);
    void verifyEmail(String email, String token);
    LoginResponseDTO refreshToken(String authorizationHeader);
    void logout(String authorizationHeader);
    void forgotPassword(String email);
}
