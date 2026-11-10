package com.booksmith.identity.internal.author;

import com.booksmith.common.data.PersonalDataArchive;
import com.booksmith.common.data.PersonalDataContributor;
import com.booksmith.identity.internal.user.UserRepository;
import com.booksmith.media.api.ImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** "Baixar tudo": a conta (sem senha nem códigos) e o perfil de autor, com a foto. */
@Component
@RequiredArgsConstructor
class AccountDataContributor implements PersonalDataContributor {

    private final UserRepository userRepository;
    private final AuthorProfileRepository profileRepository;
    private final ImageStorage imageStorage;

    @Override
    public String name() {
        return "conta";
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(UUID ownerId, PersonalDataArchive archive) {
        userRepository.findById(ownerId).ifPresent(user -> archive.json("conta/conta.json", new Account(
                user.getId(), user.getEmail(), user.getUsername(), user.getFirstName(), user.getLastName(),
                user.getPhone(), user.getDocument(), user.getBirthDate(), user.getProvider() == null ? null : user.getProvider().name(),
                user.isGoogleLinked(), user.isEmailVerified(), user.getCreatedAt(), user.getLastLoginAt())));
        profileRepository.findById(ownerId).ifPresent(profile -> {
            String avatar = null;
            if (profile.getAvatarUrl() != null) {
                var image = imageStorage.read(profile.getAvatarUrl()).orElse(null);
                if (image != null) {
                    avatar = "conta/foto." + image.extension();
                    archive.bytes(avatar, image.bytes());
                }
            }
            archive.json("conta/perfil-de-autor.json", new Profile(profile.getPenName(), profile.getBio(), avatar,
                    profile.getAudience(), profile.getLanguage(), profile.getSocialLinks(), profile.getBookGenres(),
                    profile.getWritingStyles(), profile.getAuthorReferences(), profile.getUpdatedAt()));
        });
    }

    /** {@code document}: CPF, se foi informado. */
    record Account(UUID id, String email, String username, String firstName, String lastName, String phone,
                   String document, LocalDate birthDate, String provider, boolean googleLinked, boolean emailVerified,
                   Instant createdAt, Instant lastLoginAt) {
    }

    /** {@code photo}: caminho da foto dentro do .zip. */
    record Profile(String penName, String bio, String photo, String audience, String language, List<String> socialLinks,
                   List<String> genres, List<String> writingStyles, List<String> references, Instant updatedAt) {
    }
}
