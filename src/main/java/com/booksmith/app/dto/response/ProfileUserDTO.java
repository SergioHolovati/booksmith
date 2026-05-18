package com.booksmith.app.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Builder
public class ProfileUserDTO {
    private String id;
    private String username;
    private String name;
    private String phone;
    private String email;
    private String document;
    private String accountStatus;
}
