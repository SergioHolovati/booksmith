package com.booksmith.identity.internal.author;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuthorProfileRepository extends JpaRepository<AuthorProfile, UUID> {
}
