package com.booksmith.app.dto.request;

import lombok.Getter;

import java.util.List;

@Getter
public class AuthorRequestDTO {
    private String avatar;
    private List<String> socialLinks;
    private String bio;
    private String penName;
    private List<String> bookGenres;
    private List<String> writingStyle;
    private List<String> authorReferences;
    private String audience;
    private String language;
}
