package com.dmc.backend.models.hazard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Objects;

/**
 * Metadata only. storageKey is private and never a public download URL.
 * Upload services must validate real image bytes and durability before creating this value.
 */
public record PhotoEvidence(
        String id, String storageKey, String originalFilename, String contentType,
        long sizeBytes, String sha256, @Valid @NotNull UserReference uploadedBy,
        Instant uploadedAt, Instant capturedAt) {
    public PhotoEvidence {
        ModelChecks.nonblank(id, "evidence id");
        ModelChecks.nonblank(storageKey, "storageKey");
        ModelChecks.nonblank(originalFilename, "originalFilename");
        if (originalFilename.contains("/") || originalFilename.contains("\\")
                || originalFilename.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("originalFilename must be a plain filename");
        }
        ModelChecks.nonblank(contentType, "contentType");
        if (!contentType.startsWith("image/")) {
            throw new IllegalArgumentException("contentType must identify an image");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 digest");
        }
        Objects.requireNonNull(uploadedBy, "uploadedBy");
        uploadedAt = ModelChecks.timestamp(uploadedAt, "uploadedAt");
        if (capturedAt != null) {
            capturedAt = ModelChecks.timestamp(capturedAt, "capturedAt");
        }
    }
}
