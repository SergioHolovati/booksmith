package com.booksmith.identity.internal.author;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.user.User;
import com.booksmith.identity.internal.user.UserRepository;
import com.booksmith.media.api.ImageCategory;
import com.booksmith.media.api.ImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final AuthorProfileRepository authorProfileRepository;
    private final ImageStorage imageStorage;

    @Transactional(readOnly = true)
    public Profile get(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("user_not_found", "Usuário não encontrado."));
        return new Profile(user, authorProfileRepository.findById(userId));
    }

    @Transactional
    public AuthorProfile createAuthor(UUID userId, AuthorData data) {
        if (authorProfileRepository.existsById(userId)) {
            throw ApiException.conflict("author_profile_exists", "O perfil de autor já existe.", Map.of());
        }
        AuthorProfile profile = AuthorProfile.builder().userId(userId).build();
        apply(profile, data);
        if (data.avatarDataUrl() != null) {
            profile.setAvatarUrl(imageStorage.store(ImageCategory.AVATAR, userId, data.avatarDataUrl()).url());
        }
        return authorProfileRepository.save(profile);
    }

    @Transactional
    public AuthorProfile updateAuthor(UUID userId, AuthorData data) {
        AuthorProfile profile = authorProfileRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("author_profile_not_found", "Perfil de autor não encontrado."));
        apply(profile, data);

        if (data.avatarDataUrl() != null) {
            String previousAvatar = profile.getAvatarUrl();
            profile.setAvatarUrl(imageStorage.store(ImageCategory.AVATAR, userId, data.avatarDataUrl()).url());
            imageStorage.delete(previousAvatar);
        }
        return profile;
    }

    private static void apply(AuthorProfile profile, AuthorData data) {
        profile.setPenName(data.penName().trim());
        profile.setBio(data.bio());
        profile.setAudience(data.audience());
        profile.setLanguage(data.language());
        profile.setSocialLinks(listOrEmpty(data.socialLinks()));
        profile.setBookGenres(listOrEmpty(data.bookGenres()));
        profile.setWritingStyles(listOrEmpty(data.writingStyles()));
        profile.setAuthorReferences(listOrEmpty(data.authorReferences()));
    }

    private static List<String> listOrEmpty(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    public record Profile(User user, Optional<AuthorProfile> author) {
    }

    /** @param avatarDataUrl nova imagem como data URL; nulo mantém a atual */
    public record AuthorData(String penName, String bio, String audience, String language,
                             List<String> socialLinks, List<String> bookGenres, List<String> writingStyles,
                             List<String> authorReferences, String avatarDataUrl) {
    }
}
