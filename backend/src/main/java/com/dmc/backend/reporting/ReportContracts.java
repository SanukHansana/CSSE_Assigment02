package com.dmc.backend.reporting;

import com.dmc.backend.models.hazard.HazardReport.ReportedLocation;
import com.dmc.backend.models.hazard.HazardReport.PhotoEvidence;
import com.dmc.backend.models.hazard.HazardReport.ReportHistoryEvent;
import com.dmc.backend.models.hazard.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** Explicit projections keep private evidence keys and account credentials out of HTTP responses. */
public final class ReportContracts {
    private ReportContracts() { }

    public record LocationRequest(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Size(max = 200) String areaLabel, Instant capturedAt) {
        public ReportedLocation toModel() {
            return new ReportedLocation(latitude, longitude, areaLabel, capturedAt);
        }
    }
    public record CreateDraftRequest(HazardType hazardType, @Size(max = 500) String description,
            @Valid LocationRequest location, Instant clientCapturedAt, ReportSource source) { }
    /** PATCH replaces these three draft fields; null clears an optional field. */
    public record UpdateDraftRequest(@NotNull @PositiveOrZero Long expectedVersion,
            HazardType hazardType, @Size(max = 500) String description, @Valid LocationRequest location) { }
    public record VersionRequest(@NotNull @PositiveOrZero Long expectedVersion) { }
    public record ReporterResponse(String displayName, ReporterType type) { }
    public record PhotoResponse(String id, String originalFilename, String contentType, long sizeBytes,
            Instant uploadedAt, Instant capturedAt, String viewUrl, String downloadUrl) {
        static PhotoResponse from(HazardReport report, String photoPath) {
            PhotoEvidence photo = report.getPhoto();
            if (photo == null) return null;
            String base = "/api/dmc/ground-reports/" + report.getId() + "/" + photoPath;
            return new PhotoResponse(photo.id(), photo.originalFilename(), photo.contentType(),
                    photo.sizeBytes(), photo.uploadedAt(), photo.capturedAt(), base, base + "/download");
        }
    }
    public record HistoryResponse(String id, HistoryEventType type, ReportStatus previousStatus,
            ReportStatus status, String actorDisplayName, Instant occurredAt) {
        static HistoryResponse from(ReportHistoryEvent event) {
            return new HistoryResponse(event.id(), event.type(), event.previousStatus(), event.status(),
                    event.actor().displayName(), event.occurredAt());
        }
    }
    public record ReviewResponse(String officerDisplayName, Instant startedAt, CredibilityChecklist checklist, String comments) {
        static ReviewResponse from(ReportReview review) {
            return review == null ? null : new ReviewResponse(review.officer().displayName(), review.startedAt(), review.checklist(), review.comments());
        }
    }
    public record VerificationResponse(String reference, String officerDisplayName, VerificationDecision decision,
            String comments, String rejectionReason, CredibilityChecklist checklist, Instant decidedAt) {
        static VerificationResponse from(ReportVerification verification) {
            return verification == null ? null : new VerificationResponse(verification.reference(), verification.officer().displayName(),
                    verification.decision(), verification.comments(), verification.rejectionReason(), verification.checklist(), verification.decidedAt());
        }
    }
    public record ReportResponse(String id, String reference, Long version, ReporterResponse reporter,
            ReportSource source, ReportStatus status, HazardType hazardType, String description,
            ReportedLocation location, PhotoResponse photo, Instant clientCapturedAt,
            Instant createdAt, Instant updatedAt, Instant submittedAt, List<HistoryResponse> history, ReviewResponse review, VerificationResponse verification) {
        public static ReportResponse from(HazardReport report) { return from(report, "photo"); }
        public static ReportResponse fromForReview(HazardReport report) { return from(report, "review/photo"); }
        public static ReportResponse fromForAssessment(HazardReport report) { return from(report, "assessment/photo"); }
        private static ReportResponse from(HazardReport report, String photoPath) {
            return new ReportResponse(report.getId(), report.getReference(), report.getVersion(),
                    new ReporterResponse(report.getReporter().user().displayName(), report.getReporter().type()),
                    report.getSource(), report.getStatus(), report.getHazardType(), report.getDescription(),
                    report.getLocation(), PhotoResponse.from(report, photoPath), report.getClientCapturedAt(),
                    report.getCreatedAt(), report.getUpdatedAt(), report.getSubmittedAt(),
                    report.getHistory().stream().map(HistoryResponse::from).toList(),
                    ReviewResponse.from(report.getReview()), VerificationResponse.from(report.getVerification()));
        }
    }
    public record PageResponse(List<ReportResponse> items, int page, int size, long totalElements, int totalPages) { }
}
