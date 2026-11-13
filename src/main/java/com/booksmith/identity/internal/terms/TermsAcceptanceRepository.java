package com.booksmith.identity.internal.terms;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface TermsAcceptanceRepository extends JpaRepository<TermsAcceptance, UUID> {
}
