package com.booksmith.app.service.impl;

import com.booksmith.app.dto.config.UserContextDTO;
import com.booksmith.app.dto.request.AuthorRequestDTO;
import com.booksmith.app.dto.response.ProfileAuthorResponseDTO;
import com.booksmith.app.dto.response.ProfileResponseDTO;
import com.booksmith.app.model.Author;
import com.booksmith.app.model.User;
import com.booksmith.app.repository.AuthorRepository;
import com.booksmith.app.repository.ProfileRepository;
import com.booksmith.app.service.LocalStorageService;
import com.booksmith.app.service.ProfileService;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.annotations.NotFound;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

@Slf4j
@Service
public class ProfileServiceImpl implements ProfileService {
    private final ProfileRepository profileRepository;
    private final UserContextDTO userContextDTO;
    private final LocalStorageService localStorageService;
    private final AuthorRepository authorRepository;

    public ProfileServiceImpl(ProfileRepository profileRepository,
                              UserContextDTO userContextDTO,
                              LocalStorageService localStorageService,
                              AuthorRepository authorRepository) {
        this.profileRepository = profileRepository;
        this.userContextDTO = userContextDTO;
        this.localStorageService = localStorageService;
        this.authorRepository = authorRepository;
    }

    @Override
    public ProfileResponseDTO profile() {

        String userId = userContextDTO.getUserId();
        User user = profileRepository.findById(userId).orElseThrow(() -> new UsernameNotFoundException("User not found"));
        log.info("Getting profile userId: {}",userId);
        return ProfileResponseDTO.builder()
                .profileAuthor(ObjectUtils.isEmpty(user.getAuthor())
                                                    ? null : Author.toDTO(user.getAuthor()))
                .profileUser(User.toProfile(user)).build();
    }

    @Override
    @Transactional
    public void createAuthor(AuthorRequestDTO authorRequestDTO) {
        try{
            var userId = userContextDTO.getUserId();
            String path = localStorageService.saveAuthorAvatar(userId, authorRequestDTO.getAvatar());
            User user = profileRepository.findById(userId).orElseThrow(() -> new UsernameNotFoundException("User not found"));
            if(user.getAuthor() != null) throw new UsernameNotFoundException("Author profile already exists");
            Author author = Author.toEntity(authorRequestDTO,path,user);
            authorRepository.save(author);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @Transactional
    public void updateAuthor(AuthorRequestDTO request) {
        String uploadedAvatar = null;
        try {
            String userId =
                    userContextDTO.getUserId();

            User user =
                    profileRepository
                            .findById(userId)
                            .orElseThrow(
                                    () ->
                                            new RuntimeException(
                                                    "Usuário não encontrado"
                                            )
                            );

            Author author =
                    user.getAuthor();

            if (author == null) {
                throw new RuntimeException(
                        "Perfil de autor não encontrado"
                );
            }

            String avatarPath =
                    author.getAvatarUrl();

            boolean hasNewAvatar =
                    request.getAvatar() != null
                            && !request.getAvatar()
                            .isBlank()
                            && request.getAvatar()
                            .startsWith(
                                    "data:image"
                            );

            if (hasNewAvatar) {

                uploadedAvatar =
                        localStorageService
                                .saveAuthorAvatar(
                                        userId,
                                        request.getAvatar()
                                );

                avatarPath =
                        uploadedAvatar;
            }

            author.update(
                    request,
                    avatarPath
            );

        } catch (Exception e) {

            if (uploadedAvatar != null) {
                localStorageService.delete(
                        uploadedAvatar
                );
            }

            throw e;
        }
    }
}
