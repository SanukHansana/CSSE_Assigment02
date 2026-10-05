package com.dmc.backend.models.hazard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Single MongoDB aggregate. Controlled mutations preserve domain rules; there are no public setters.
 * Authorization belongs in application services, not in these identity snapshots.
 * A mutation is not submission success until an acknowledged repository save succeeds.
 */
@Document(collection = "hazard_reports")
public class HazardReport {
    @Id
    @NotBlank
    private String id;
    @NotBlank
    private String reference;
    @Version
    private Long version;
    private int schemaVersion = 1;
    @Valid
    @NotNull
    private ReporterIdentity reporter;
    @NotNull
    private ReportSource source;
    @NotNull
    private ReportStatus status;
    private HazardType hazardType;
    @Size(max = 500)
    private String description;
    @Valid
    private ReportedLocation location;
    @Valid
    private PhotoEvidence photo;
    private Instant clientCapturedAt;
    @NotNull
    private Instant createdAt;
    @NotNull
    private Instant updatedAt;
    private Instant submittedAt;
    @Valid
    private ReportReview review;
    @Valid
    private ReportVerification verification;
    @NotNull
    private List<@Valid ReportHistoryEvent> history = new ArrayList<>();

    /** Used by Spring Data for hydration; new domain objects must use draft(). */
    private HazardReport() { }

    public static HazardReport draft(ReporterIdentity reporter, ReportSource source,
                                     Instant clientCapturedAt, Clock clock) {
        HazardReport report = new HazardReport();
        report.reporter = Objects.requireNonNull(reporter, "reporter");
        report.source = Objects.requireNonNull(source, "source");
        report.createdAt = HazardReport.timestamp(clock.instant(), "createdAt");
        report.updatedAt = report.createdAt;
        report.clientCapturedAt = clientCapturedAt == null ? null
                : HazardReport.timestamp(clientCapturedAt, "clientCapturedAt");
        report.id = UUID.randomUUID().toString();
        report.reference = reference("HR", report.createdAt);
        report.status = ReportStatus.DRAFT;
        report.append(HistoryEventType.DRAFT_CREATED, null, reporter.user(), report.createdAt);
        return report;
    }

    /** Incomplete drafts are allowed, but supplied data must still satisfy its constraints. */
    public void updateDraft(HazardType hazardType, String description,
                            ReportedLocation location, Clock clock) {
        requireState(ReportStatus.DRAFT);
        HazardReport.description(description);
        Instant now = now(clock);
        this.hazardType = hazardType;
        this.description = description;
        this.location = location;
        append(HistoryEventType.DRAFT_UPDATED, status, reporter.user(), now);
    }

    public void replacePhoto(PhotoEvidence photo, Clock clock) {
        requireState(ReportStatus.DRAFT);
        Objects.requireNonNull(photo, "photo");
        if (!photo.uploadedBy().subjectId().equals(reporter.user().subjectId())) {
            throw new IllegalArgumentException("photo must belong to the reporter");
        }
        Instant now = now(clock);
        if (photo.uploadedAt().isAfter(now)) {
            throw new IllegalArgumentException("photo upload time cannot follow its attachment time");
        }
        this.photo = photo;
        append(HistoryEventType.EVIDENCE_REPLACED, status, reporter.user(), now);
    }

    public void removePhoto(Clock clock) {
        requireState(ReportStatus.DRAFT);
        Instant now = now(clock);
        this.photo = null;
        append(HistoryEventType.EVIDENCE_REMOVED, status, reporter.user(), now);
    }

    public void submit(Clock clock) {
        requireState(ReportStatus.DRAFT);
        requireSubmissionFields();
        Instant now = now(clock);
        submittedAt = now;
        transition(ReportStatus.SUBMITTED, HistoryEventType.SUBMITTED, reporter.user(), now);
    }

    public void startReview(UserReference officer, Clock clock) {
        requireState(ReportStatus.SUBMITTED);
        Objects.requireNonNull(officer, "officer");
        Instant now = now(clock);
        review = new ReportReview(officer, now, CredibilityChecklist.unassessed(), null);
        transition(ReportStatus.UNDER_REVIEW, HistoryEventType.REVIEW_STARTED, officer, now);
    }

    public void updateReview(UserReference officer, CredibilityChecklist checklist,
                             String comments, Clock clock) {
        requireReviewingOfficer(officer);
        Objects.requireNonNull(checklist, "checklist");
        Instant now = now(clock);
        review = new ReportReview(review.officer(), review.startedAt(), checklist, comments);
        append(HistoryEventType.REVIEW_UPDATED, status, officer, now);
    }

    /** Domain transition only; Stage 3 will add authorization, persistence, and conflict responses. */
    public void decide(UserReference officer, VerificationDecision decision,
                       String rejectionReason, Clock clock) {
        requireReviewingOfficer(officer);
        Objects.requireNonNull(decision, "decision");
        Instant now = now(clock);
        // Construct/validate everything before mutating status, history, or decision.
        ReportVerification candidate = new ReportVerification(reference("RV", now),
                review.officer(), decision, review.comments(), rejectionReason, review.checklist(), now);
        verification = candidate;
        ReportStatus next = decision == VerificationDecision.VERIFIED
                ? ReportStatus.VERIFIED : ReportStatus.REJECTED;
        transition(next, decision == VerificationDecision.VERIFIED
                ? HistoryEventType.VERIFIED : HistoryEventType.REJECTED, officer, now);
    }

    /** Predicate for the future assessment boundary; no warning or assessment is created. */
    public boolean isEligibleForAssessment() {
        return status == ReportStatus.VERIFIED && isStateConsistent();
    }

    private void requireSubmissionFields() {
        Objects.requireNonNull(hazardType, "hazardType is required for submission");
        HazardReport.nonblank(description, "description");
        HazardReport.description(description);
        Objects.requireNonNull(location, "location is required for submission");
        Objects.requireNonNull(photo, "photo is required for submission");
    }

    private void requireReviewingOfficer(UserReference officer) {
        requireState(ReportStatus.UNDER_REVIEW);
        Objects.requireNonNull(officer, "officer");
        if (!review.officer().subjectId().equals(officer.subjectId())) {
            throw new IllegalStateException("only the assigned reviewing officer may change this review");
        }
    }

    private void requireState(ReportStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("expected " + expected + " but report is " + status);
        }
    }

    private Instant now(Clock clock) {
        Instant now = HazardReport.timestamp(clock.instant(), "server time");
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("server event time cannot precede the previous event");
        }
        return now;
    }

    private void transition(ReportStatus next, HistoryEventType type, UserReference actor, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("invalid transition from " + status + " to " + next);
        }
        ReportStatus previous = status;
        status = next;
        append(type, previous, actor, now);
    }

    private void append(HistoryEventType type, ReportStatus previous, UserReference actor, Instant now) {
        history.add(new ReportHistoryEvent(UUID.randomUUID().toString(), type, previous, status, actor, now));
        updatedAt = now;
    }

    private static String reference(String prefix, Instant now) {
        // Human-readable, collision-resistant; screenshots' sequential suffix is illustrative.
        return prefix + "-" + now.atZone(ZoneOffset.UTC).getYear() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
    }

    /** Persistence validation also catches malformed objects populated directly by the mapper. */
    @AssertTrue(message = "report state, decision, and timeline must be consistent")
    public boolean isStateConsistent() {
        if (status == null || createdAt == null || updatedAt == null || reporter == null
                || history == null || history.isEmpty() || schemaVersion != 1
                || updatedAt.isBefore(createdAt)) {
            return false;
        }
        if (photo != null && (!photo.uploadedBy().subjectId().equals(reporter.user().subjectId())
                || photo.uploadedAt().isAfter(updatedAt))) {
            return false;
        }
        if (status == ReportStatus.DRAFT) {
            if (submittedAt != null || review != null || verification != null) return false;
        } else {
            if (hazardType == null || description == null || description.isBlank() || location == null
                    || photo == null || submittedAt == null || submittedAt.isBefore(createdAt)
                    || submittedAt.isAfter(updatedAt)) return false;
            if (status == ReportStatus.SUBMITTED) {
                if (review != null || verification != null) return false;
            } else {
                if (review == null || review.startedAt().isBefore(submittedAt)
                        || review.startedAt().isAfter(updatedAt)) return false;
                if (status == ReportStatus.UNDER_REVIEW) {
                    if (verification != null) return false;
                } else if (verification == null || !verification.decision().name().equals(status.name())
                        || !verification.officer().subjectId().equals(review.officer().subjectId())
                        || !verification.checklist().equals(review.checklist())
                        || !Objects.equals(verification.comments(), review.comments())
                        || verification.decidedAt().isBefore(review.startedAt())
                        || !verification.decidedAt().equals(updatedAt)) return false;
            }
        }
        Instant previousTime = createdAt;
        ReportStatus previousStatus = null;
        for (int i = 0; i < history.size(); i++) {
            ReportHistoryEvent event = history.get(i);
            if (event == null || !eventMatchesState(event) || event.occurredAt().isBefore(previousTime)
                    || event.occurredAt().isAfter(updatedAt)) return false;
            if (i == 0) {
                if (event.type() != HistoryEventType.DRAFT_CREATED || event.previousStatus() != null
                        || event.status() != ReportStatus.DRAFT || !event.occurredAt().equals(createdAt)) return false;
            } else if (event.previousStatus() != previousStatus
                    || (event.status() != previousStatus && !previousStatus.canTransitionTo(event.status()))) {
                return false;
            }
            previousStatus = event.status();
            previousTime = event.occurredAt();
        }
        return previousStatus == status && previousTime.equals(updatedAt);
    }

    private boolean eventMatchesState(ReportHistoryEvent event) {
        return switch (event.type()) {
            case DRAFT_CREATED -> event.previousStatus() == null && event.status() == ReportStatus.DRAFT
                    && event.actor().subjectId().equals(reporter.user().subjectId());
            case DRAFT_UPDATED, EVIDENCE_REPLACED, EVIDENCE_REMOVED ->
                    event.previousStatus() == ReportStatus.DRAFT && event.status() == ReportStatus.DRAFT
                    && event.actor().subjectId().equals(reporter.user().subjectId());
            case SUBMITTED -> event.previousStatus() == ReportStatus.DRAFT && event.status() == ReportStatus.SUBMITTED
                    && event.occurredAt().equals(submittedAt)
                    && event.actor().subjectId().equals(reporter.user().subjectId());
            case REVIEW_STARTED -> review != null && event.previousStatus() == ReportStatus.SUBMITTED
                    && event.status() == ReportStatus.UNDER_REVIEW && event.occurredAt().equals(review.startedAt())
                    && event.actor().subjectId().equals(review.officer().subjectId());
            case REVIEW_UPDATED -> review != null && event.previousStatus() == ReportStatus.UNDER_REVIEW
                    && event.status() == ReportStatus.UNDER_REVIEW
                    && event.actor().subjectId().equals(review.officer().subjectId());
            case VERIFIED, REJECTED -> verification != null && event.previousStatus() == ReportStatus.UNDER_REVIEW
                    && event.status().name().equals(event.type().name())
                    && event.type().name().equals(verification.decision().name())
                    && event.occurredAt().equals(verification.decidedAt())
                    && event.actor().subjectId().equals(verification.officer().subjectId());
        };
    }

    public String getId() { return id; }
    public String getReference() { return reference; }
    public String getDescription() { return description; }
    public Long getVersion() { return version; }
    public int getSchemaVersion() { return schemaVersion; }
    public ReporterIdentity getReporter() { return reporter; }
    public ReportSource getSource() { return source; }
    public ReportStatus getStatus() { return status; }
    public HazardType getHazardType() { return hazardType; }
    public ReportedLocation getLocation() { return location; }
    public PhotoEvidence getPhoto() { return photo; }
    public Instant getClientCapturedAt() { return clientCapturedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public ReportReview getReview() { return review; }
    public ReportVerification getVerification() { return verification; }
    public List<ReportHistoryEvent> getHistory() { return List.copyOf(history); }

    // Small report values live beside the aggregate; their persisted fields stay unchanged.
    /**
     * Opaque reference plus display-name snapshot, to be populated from trusted authentication.
     * This is not a user account, authentication module, or caller-supplied authorization claim.
     */
    public record UserReference(@NotBlank String subjectId, @NotBlank String displayName) {
        public UserReference {
            HazardReport.nonblank(subjectId, "subjectId");
            HazardReport.nonblank(displayName, "displayName");
        }
    }

    /** Reporter type is resolved from the authenticated account by a future application service. */
    public record ReporterIdentity(@Valid @NotNull UserReference user, @NotNull ReporterType type) {
        public ReporterIdentity {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(type, "type");
        }
    }

    /** WGS84 coordinates. Capture time is client-originated and separate from server receipt. */
    public record ReportedLocation(double latitude, double longitude, String areaLabel, Instant capturedAt) {
        public ReportedLocation {
            if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
                throw new IllegalArgumentException("latitude must be finite and between -90 and 90");
            }
            if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
                throw new IllegalArgumentException("longitude must be finite and between -180 and 180");
            }
            if (capturedAt != null) {
                capturedAt = HazardReport.timestamp(capturedAt, "capturedAt");
            }
        }
    }

    /**
     * Metadata only. storageKey is private and never a public download URL.
     * Upload services must validate real image bytes and durability before creating this value.
     */
    public record PhotoEvidence(
            String id, String storageKey, String originalFilename, String contentType,
            long sizeBytes, String sha256, @Valid @NotNull UserReference uploadedBy,
            Instant uploadedAt, Instant capturedAt) {
        public PhotoEvidence {
            HazardReport.nonblank(id, "evidence id");
            HazardReport.nonblank(storageKey, "storageKey");
            HazardReport.nonblank(originalFilename, "originalFilename");
            if (originalFilename.contains("/") || originalFilename.contains("\\")
                    || originalFilename.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("originalFilename must be a plain filename");
            }
            HazardReport.nonblank(contentType, "contentType");
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
            uploadedAt = HazardReport.timestamp(uploadedAt, "uploadedAt");
            if (capturedAt != null) {
                capturedAt = HazardReport.timestamp(capturedAt, "capturedAt");
            }
        }
    }

    /** Server event time only. Previous state is null for the initial draft creation event. */
    public record ReportHistoryEvent(String id, @NotNull HistoryEventType type,
            ReportStatus previousStatus, @NotNull ReportStatus status,
            @Valid @NotNull UserReference actor, Instant occurredAt) {
        public ReportHistoryEvent {
            HazardReport.nonblank(id, "history id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(actor, "actor");
            occurredAt = HazardReport.timestamp(occurredAt, "occurredAt");
        }
    }

    static String nonblank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static Instant timestamp(Instant value, String field) {
        return Objects.requireNonNull(value, field).truncatedTo(ChronoUnit.MILLIS);
    }

    static void description(String value) {
        if (value != null && value.length() > 500) {
            throw new IllegalArgumentException("description must contain at most 500 characters");
        }
    }
}
