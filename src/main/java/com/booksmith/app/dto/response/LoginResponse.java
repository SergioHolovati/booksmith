package com.booksmith.app.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Builder
public class LoginResponse {
    private String userId;
    private String userName;
    private String jwt;

}
