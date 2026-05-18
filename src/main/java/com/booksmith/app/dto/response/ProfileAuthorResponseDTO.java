package com.booksmith.app.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@Builder
public class ProfileAuthorResponseDTO {
    private String avatarUrl;
    private List<String> socialLinks;
    private String bio;
    private String penName;
    private List<String> bookGenres;
    private List<String> writingStyle;
    private List<String> authorReferences;
    private String audience;
    private String language;

}
