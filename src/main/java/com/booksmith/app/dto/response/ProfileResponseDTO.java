package com.booksmith.app.dto.response;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileResponseDTO {
    private ProfileUserDTO profileUser;
    private ProfileAuthorResponseDTO profileAuthor;
}
