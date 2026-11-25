package com.booksmith.identity.internal.user;

import com.booksmith.identity.api.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class JpaUserDirectory implements UserDirectory {

    private final UserRepository userRepository;
    private final com.booksmith.identity.internal.author.AuthorProfileRepository authorProfiles;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> email(UUID userId) {
        return userRepository.findById(userId).map(User::getEmail);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> displayName(UUID userId) {
        return userRepository.findById(userId)
                .map(user -> user.displayName() == null || user.displayName().isBlank() ? user.getUsername() : user.displayName());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> publicName(UUID userId) {
        return authorProfiles.findById(userId)
                .map(com.booksmith.identity.internal.author.AuthorProfile::getPenName)
                .filter(name -> !name.isBlank());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> idByEmail(String email) {
        return email == null ? Optional.empty() : userRepository.findByEmailIgnoreCase(email.strip()).map(User::getId);
    }
}
