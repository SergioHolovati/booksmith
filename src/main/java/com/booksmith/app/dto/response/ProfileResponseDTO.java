package com.booksmith.app.dto.response;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileResponseDTO {
    private String id;
    private String username;
    private String name;
    private String phone;
    private String email;
    private String document;
    private String accountStatus;

}
