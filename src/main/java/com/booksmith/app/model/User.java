package com.booksmith.app.model;

import com.booksmith.app.dto.request.RegisterRequest;
import com.booksmith.app.dto.response.ProfileResponse;
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
    @CreationTimestamp
    private LocalDateTime createdAt;
    @Column
    @UpdateTimestamp
    private LocalDateTime updatedAt;
    @Column
    private LocalDateTime lastLogin;

    public static User toEntity(RegisterRequest request){
        return User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .username(request.getUsername())
                .document(request.getDocument())
                .email(request.getEmail())
                .password(request.getPassword())
                .phone(request.getPhone())
                .build();
    }

    public static ProfileResponse toProfile(User user){
        return ProfileResponse.builder()
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
