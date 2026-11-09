package com.booksmith.identity.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.identity.internal.author.AuthorProfile;
import com.booksmith.identity.internal.author.ProfileService;
import com.booksmith.identity.internal.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
class ProfileController {

    private final ProfileService profileService;
    private final CurrentUser currentUser;

    @GetMapping
    ProfileResponse get() {
        var profile = profileService.get(currentUser.id());
        return new ProfileResponse(
                UserView.from(profile.user()),
                profile.author().map(AuthorView::from).orElse(null));
    }

    @PostMapping("/author")
    @ResponseStatus(HttpStatus.CREATED)
    AuthorView createAuthor(@RequestBody @Valid AuthorProfileRequest request) {
        return AuthorView.from(profileService.createAuthor(currentUser.id(), request.toData()));
    }

    @PutMapping("/author")
    AuthorView updateAuthor(@RequestBody @Valid AuthorProfileRequest request) {
        return AuthorView.from(profileService.updateAuthor(currentUser.id(), request.toData()));
    }

    record AuthorProfileRequest(
            @NotBlank(message = "Informe o nome de autor.") @Size(max = 80) String penName,
            @NotBlank(message = "Escreva uma bio curta.") @Size(max = 2000) String bio,
            @NotBlank(message = "Informe o público.") String audience,
            @NotBlank(message = "Informe o idioma.") String language,
            @Size(max = 10) List<@Size(max = 300) String> socialLinks,
            @NotEmpty(message = "Escolha ao menos um gênero.") List<@Size(max = 60) String> bookGenres,
            List<@Size(max = 60) String> writingStyles,
            List<@Size(max = 120) String> authorReferences,
            String avatar) {

        ProfileService.AuthorData toData() {
            // O frontend reenvia a URL atual quando o avatar não muda; só data URLs são uploads novos
            String avatarDataUrl = avatar != null && avatar.startsWith("data:image") ? avatar : null;
            return new ProfileService.AuthorData(penName, bio, audience, language, socialLinks, bookGenres,
                    writingStyles, authorReferences, avatarDataUrl);
        }
    }

    record ProfileResponse(UserView user, AuthorView author) {
    }

    record UserView(UUID id, String username, String firstName, String lastName, String displayName,
                    String email, boolean emailVerified, LocalDate birthDate) {

        static UserView from(User user) {
            return new UserView(user.getId(), user.getUsername(), user.getFirstName(), user.getLastName(),
                    user.displayName(), user.getEmail(), user.isEmailVerified(), user.getBirthDate());
        }
    }

    record AuthorView(String penName, String bio, String avatarUrl, String audience, String language,
                      List<String> socialLinks, List<String> bookGenres, List<String> writingStyles,
                      List<String> authorReferences) {

        static AuthorView from(AuthorProfile profile) {
            return new AuthorView(profile.getPenName(), profile.getBio(), profile.getAvatarUrl(),
                    profile.getAudience(), profile.getLanguage(), profile.getSocialLinks(),
                    profile.getBookGenres(), profile.getWritingStyles(), profile.getAuthorReferences());
        }
    }
}
