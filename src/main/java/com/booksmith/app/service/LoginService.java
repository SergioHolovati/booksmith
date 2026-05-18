package com.booksmith.app.service;

import com.booksmith.app.dto.request.LoginRequest;
import com.booksmith.app.dto.response.LoginResponse;
import com.booksmith.app.dto.request.RegisterRequest;

public interface LoginService {
    LoginResponse login(LoginRequest loginRequest);
    void register(RegisterRequest register);
    LoginResponse refreshToken(String authorizationHeader);
    void logout(String authorizationHeader);
}
