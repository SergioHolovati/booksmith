package com.booksmith.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class AuthorRequestDTO {
    private String avatar;
    @NotBlank(message = "At least one social media is required.")
    private List<String> socialLinks;
    @NotBlank(message = "Bio is required.")
    private String bio;
    @NotBlank(message = "Public name is required.")
    private String penName;
    @NotEmpty(message = "At least one book genre is required. Example: Romance, Fantasy, Sci-fi, Espiritual.")
    private List<String> bookGenres;
    @NotEmpty(message = "At least one writing style is required. Exemple: Classic, Romance, Novel")
    private List<String> writingStyle;
    @NotEmpty(message = "At least one author is required.")
    private List<String> authorReferences;
    @NotBlank(message = "Audience is required.")
    private String audience;
    @NotBlank(message = "Language is required.")
    private String language;
}
