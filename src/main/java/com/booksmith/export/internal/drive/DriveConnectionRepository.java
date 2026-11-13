package com.booksmith.export.internal.drive;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface DriveConnectionRepository extends JpaRepository<DriveConnection, UUID> {
}
