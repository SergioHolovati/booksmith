package com.booksmith.continuity.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {
}
