package com.booksmith.app.model;

import com.booksmith.app.dto.request.RegisterRequestDTO;
import com.booksmith.app.dto.response.ProfileResponseDTO;
import com.booksmith.app.dto.response.ProfileUserDTO;
import com.booksmith.app.enums.AuthProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column
    private String firstName;
    @Column
    private String lastName;
    @Column
    private String username;
    @Column(unique = true)
    private String document;
    @Column(unique = true)
    private String email;
    @Column
    private String password;
    @Column
    private String phone;
    @Column
    private String accountStatus;
    @Column
    @Enumerated(EnumType.STRING)
    private AuthProvider provider;
    @Column(name = "google_linked", nullable = false)
    private boolean googleLinked;
    @Column(nullable = false)
    private boolean verified;
    @Column(name = "email_verification_token_hash")
    private Integer emailVerificationTokenHash;
    @Column(name = "email_verification_token_expires_at")
    private LocalDateTime emailVerificationTokenExpiresAt;
    @Column
    @CreationTimestamp
    private LocalDateTime createdAt;
    @Column
    @UpdateTimestamp
    private LocalDateTime updatedAt;
    @Column
    private LocalDateTime lastLogin;

    @OneToOne(
            mappedBy = "user",
            fetch = FetchType.EAGER
    )
    private Author author;

    public static User toEntity(RegisterRequestDTO request){
        return User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .username(request.getUsername())
                .document(request.getDocument())
                .email(request.getEmail())
                .password(request.getPassword())
                .phone(request.getPhone())
                .provider(AuthProvider.LOCAL)
                .googleLinked(false)
                .verified(false)
                .build();
    }

    public static ProfileUserDTO toProfile(User user){
        return ProfileUserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .name(user.getFirstName())
                .phone(user.getPhone())
                .email(user.getEmail())
                .document(user.getDocument())
                .accountStatus(user.getAccountStatus())
                .build();
    }
}
