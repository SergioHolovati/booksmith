package com.booksmith.app.dto.response;

import com.booksmith.app.model.User;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileResponse {
    private String id;
    private String username;
    private String name;
    private String phone;
    private String email;
    private String document;
    private String accountStatus;

}
