package com.booksmith.app.model;

import com.booksmith.app.dto.request.BookRequestDTO;
import com.booksmith.app.dto.response.BookResponseDTO;
import com.booksmith.app.dto.response.ChapterResponseDTO;
import com.booksmith.app.enums.BookStatusEnum;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.util.ObjectUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "books")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(nullable = false)
    private Author author;
    @Column
    private String title;
    @Column
    private String slug;
    @Column(columnDefinition = "TEXT")
    private String description;
    @Column
    private String coverPath;
    @Column
    private boolean published;
    @Column
    private LocalDate publishDate;
    @Column(nullable = false)
    private boolean isDeleted;
    @Column
    private BookStatusEnum status;
    @Column
    @CreationTimestamp
    private LocalDateTime createdAt;
    @Column
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @OneToMany(
            mappedBy = "book",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<Chapter> chapters = new ArrayList<>();

    public static BookResponseDTO toBookResponseDTO(Book book,List<ChapterResponseDTO> chapters) {
        return BookResponseDTO.builder()
                .id(book.getId())
                .title(book.getTitle())
                .description(book.getDescription())
                .coverPath(book.getCoverPath())
                .published(book.isPublished())
                .publishedDate(book.getPublishDate())
                .updatedAt(book.getUpdatedAt())
                .chapter(chapters)
                .build();
    }

    public static Book toEntity(BookRequestDTO bookRequestDTO, Author author, String coverPath) {
        return Book.builder()
                .author(author)
                .title(bookRequestDTO.getTitle())
                .description(bookRequestDTO.getDescription())
                .coverPath(coverPath)
                .published(bookRequestDTO.isPublished())
                .publishDate(bookRequestDTO.getPlublishDate())
                .build();
    }

    public void update(BookRequestDTO bookRequestDTO, String coverPath) {
        this.title = bookRequestDTO.getTitle();
        this.description = bookRequestDTO.getDescription();
        this.coverPath = coverPath;
        this.published = bookRequestDTO.isPublished();
        this.publishDate = bookRequestDTO.getPlublishDate();
    }
}
