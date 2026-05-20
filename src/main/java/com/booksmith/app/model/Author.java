package com.booksmith.app.model;

import com.booksmith.app.dto.request.AuthorRequestDTO;
import com.booksmith.app.dto.response.ProfileAuthorResponseDTO;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "authors")
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            nullable = false,
            unique = true
    )
    private User user;
    @Column
    private String avatarUrl;
    @ElementCollection
    private List<String> socialLinks;
    @Column
    private String bio;
    @Column
    private String penName;
    @ElementCollection
    private List<String> bookGenres;
    @ElementCollection
    private List<String> writingStyle;
    @ElementCollection
    private List<String> authorReferences;
    @Column
    private String audience;
    @Column
    private String language;
    @Column
    @CreationTimestamp
    private LocalDateTime createdAt;
    @Column
    @UpdateTimestamp
    private LocalDateTime updatedAt;


    public static  Author toEntity(AuthorRequestDTO requestDTO, String path, User  user) {
        return Author.builder()
                .avatarUrl(path)
                .bio(requestDTO.getBio())
                .penName(requestDTO.getPenName())
                .user(user)
                .socialLinks(requestDTO.getSocialLinks())
                .bookGenres(requestDTO.getBookGenres())
                .writingStyle(requestDTO.getWritingStyle())
                .authorReferences(requestDTO.getAuthorReferences())
                .audience(requestDTO.getAudience())
                .language(requestDTO.getLanguage())
                .build();
    }

    public static ProfileAuthorResponseDTO toDTO(Author author) {
        return ProfileAuthorResponseDTO.builder()
                .avatarUrl(author.getAvatarUrl())
                .bio(author.getBio())
                .penName(author.getPenName())
                .socialLinks(author.getSocialLinks())
                .bookGenres(author.getBookGenres())
                .writingStyle(author.getWritingStyle())
                .authorReferences(author.getAuthorReferences())
                .audience(author.getAudience())
                .language(author.getLanguage())
                .build();
    }

    public void update(
            AuthorRequestDTO request,
            String avatarPath
    ) {

        this.avatarUrl =
                avatarPath;

        this.bio =
                request.getBio();

        this.penName =
                request.getPenName();

        this.socialLinks =
                request.getSocialLinks();

        this.bookGenres =
                request.getBookGenres();

        this.writingStyle =
                request.getWritingStyle();

        this.authorReferences =
                request.getAuthorReferences();

        this.audience =
                request.getAudience();

        this.language =
                request.getLanguage();
    }
}
