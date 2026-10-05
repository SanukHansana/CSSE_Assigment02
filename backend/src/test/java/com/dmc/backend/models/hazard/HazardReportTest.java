package com.dmc.backend.models.hazard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class HazardReportTest {
    static final Instant CAPTURED = Instant.parse("2026-09-04T05:00:00Z");
    static final Instant RECEIVED = Instant.parse("2026-09-05T05:05:00Z");
    static final UserReference CITIZEN = new UserReference("citizen-1", "Kasun Perera");
    static final UserReference OFFICER = new UserReference("officer-1", "N. Fernando");
    static final CredibilityChecklist PASSED = new CredibilityChecklist(true, true, true, true);
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidator() { FACTORY.close(); }

    static Clock clock(long seconds) {
        return Clock.fixed(RECEIVED.plusSeconds(seconds), ZoneOffset.UTC);
    }

    static HazardReport draft() {
        return HazardReport.draft(new ReporterIdentity(CITIZEN, ReporterType.CITIZEN),
                ReportSource.MOBILE_APP, CAPTURED, clock(0));
    }

    static PhotoEvidence photo() {
        return new PhotoEvidence("photo-1", "private/opaque-key", "flooded-road.jpg", "image/jpeg",
                2400000, "a".repeat(64), CITIZEN, RECEIVED, CAPTURED);
    }

    static HazardReport completeDraft() {
        HazardReport report = draft();
        report.updateDraft(HazardType.FLOODING, "Water covers the access road.",
                new ReportedLocation(6.9271, 79.8612, "Colombo District, Western Province", CAPTURED), clock(1));
        report.replacePhoto(photo(), clock(2));
        return report;
    }

    static HazardReport underReview() {
        HazardReport report = completeDraft();
        report.submit(clock(3));
        report.startReview(OFFICER, clock(4));
        return report;
    }

    @ParameterizedTest
    @EnumSource(ReporterType.class)
    void bothReporterTypesCanHaveIncompleteDrafts(ReporterType type) {
        HazardReport report = HazardReport.draft(new ReporterIdentity(CITIZEN, type),
                ReportSource.WEB_PORTAL, null, clock(0));
        report.updateDraft(null, null, null, clock(1));
        assertThat(VALIDATOR.validate(report)).isEmpty();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(report.getSubmittedAt()).isNull();
        assertThat(report.getReference()).matches("HR-2026-[A-F0-9]{12}");
        assertThat(report.getVersion()).isNull();
    }

    @Test
    void serverStateMachineAllowsOnlySpecifiedTransitions() {
        for (ReportStatus from : ReportStatus.values()) {
            for (ReportStatus to : ReportStatus.values()) {
                boolean expected = (from == ReportStatus.DRAFT && to == ReportStatus.SUBMITTED)
                        || (from == ReportStatus.SUBMITTED && to == ReportStatus.UNDER_REVIEW)
                        || (from == ReportStatus.UNDER_REVIEW
                            && (to == ReportStatus.VERIFIED || to == ReportStatus.REJECTED));
                assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(expected);
            }
        }
        assertThat(Arrays.stream(ReportStatus.values()).map(Enum::name)).doesNotContain("QUEUED_FOR_SYNC");
    }

    @Test
    void submissionRequiresEachFinalFieldAndPreservesFailedDraft() {
        for (int missing = 0; missing < 4; missing++) {
            HazardReport report = draft();
            report.updateDraft(missing == 0 ? null : HazardType.FLOODING,
                    missing == 1 ? "  " : "Water rising", missing == 2 ? null
                            : new ReportedLocation(0, 0, null, null), clock(1));
            if (missing != 3) report.replacePhoto(photo(), clock(2));
            int events = report.getHistory().size();
            assertThatThrownBy(() -> report.submit(clock(3))).isInstanceOf(RuntimeException.class);
            assertThat(report.getStatus()).isEqualTo(ReportStatus.DRAFT);
            assertThat(report.getSubmittedAt()).isNull();
            assertThat(report.getHistory()).hasSize(events);
            assertThat(VALIDATOR.validate(report)).isEmpty();
        }
    }

    @Test
    void descriptionAllows500CharactersButRejects501WithoutLosingData() {
        HazardReport report = draft();
        report.updateDraft(null, "x".repeat(500), null, clock(1));
        assertThatThrownBy(() -> report.updateDraft(null, "x".repeat(501), null, clock(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(report.getDescription()).hasSize(500);
        assertThat(report.getHistory()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-90.001, 90.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidLatitude(double value) {
        assertThatThrownBy(() -> new ReportedLocation(value, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180.001, 180.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidLongitude(double value) {
        assertThatThrownBy(() -> new ReportedLocation(0, value, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBoundaryAndZeroCoordinates() {
        for (double lat : new double[] {-90, 0, 90}) {
            for (double lon : new double[] {-180, 0, 180}) {
                assertThat(new ReportedLocation(lat, lon, null, null).latitude()).isEqualTo(lat);
            }
        }
    }

    @Test
    void receiptDoesNotOverwriteOriginalCaptureTimes() {
        HazardReport report = completeDraft();
        report.submit(clock(3));
        assertThat(report.getClientCapturedAt()).isEqualTo(CAPTURED);
        assertThat(report.getLocation().capturedAt()).isEqualTo(CAPTURED);
        assertThat(report.getPhoto().capturedAt()).isEqualTo(CAPTURED);
        assertThat(report.getPhoto().uploadedAt()).isEqualTo(RECEIVED);
        assertThat(report.getCreatedAt()).isEqualTo(RECEIVED);
        assertThat(report.getSubmittedAt()).isEqualTo(RECEIVED.plusSeconds(3));
        assertThat(report.getSubmittedAt().toString()).endsWith("Z");
    }

    @Test
    void matchesMongoMillisecondPrecision() {
        Instant original = CAPTURED.plusNanos(123456789);
        HazardReport report = HazardReport.draft(new ReporterIdentity(CITIZEN, ReporterType.CITIZEN),
                ReportSource.MOBILE_APP, original, Clock.fixed(original, ZoneOffset.UTC));
        assertThat(report.getClientCapturedAt()).isEqualTo(Instant.parse("2026-09-04T05:00:00.123Z"));
        assertThat(report.getCreatedAt()).isEqualTo(report.getClientCapturedAt());
    }

    @Test
    void verifiesWithImmutableDecisionSnapshotAndTimeline() {
        HazardReport report = underReview();
        assertThat(report.isEligibleForAssessment()).isFalse();
        report.updateReview(OFFICER, PASSED, "Photo and location are consistent.", clock(5));
        report.decide(OFFICER, VerificationDecision.VERIFIED, null, clock(6));
        assertThat(report.getStatus()).isEqualTo(ReportStatus.VERIFIED);
        assertThat(report.isEligibleForAssessment()).isTrue();
        assertThat(report.getVerification().reference()).matches("RV-2026-[A-F0-9]{12}");
        assertThat(report.getVerification().officer()).isEqualTo(OFFICER);
        assertThat(report.getVerification().comments()).isEqualTo(report.getReview().comments());
        assertThat(report.getVerification().decidedAt()).isEqualTo(RECEIVED.plusSeconds(6));
        assertThat(report.getHistory()).extracting(ReportHistoryEvent::type).containsExactly(
                HistoryEventType.DRAFT_CREATED, HistoryEventType.DRAFT_UPDATED, HistoryEventType.EVIDENCE_REPLACED,
                HistoryEventType.SUBMITTED, HistoryEventType.REVIEW_STARTED, HistoryEventType.REVIEW_UPDATED,
                HistoryEventType.VERIFIED);
        assertThat(VALIDATOR.validate(report)).isEmpty();
        assertThatThrownBy(() -> report.getHistory().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsWithReasonEvenWhenChecklistIsUnassessed() {
        HazardReport report = underReview();
        report.decide(OFFICER, VerificationDecision.REJECTED, "Photo shows a different location.", clock(5));
        assertThat(report.getStatus()).isEqualTo(ReportStatus.REJECTED);
        assertThat(report.isEligibleForAssessment()).isFalse();
        assertThat(report.getVerification().rejectionReason()).isNotBlank();
        assertThat(VALIDATOR.validate(report)).isEmpty();
    }

    @Test
    void failedDecisionLeavesReviewAndTimelineUntouched() {
        HazardReport report = underReview();
        int events = report.getHistory().size();
        for (String reason : new String[] {null, "", " \n "}) {
            assertThatThrownBy(() -> report.decide(OFFICER, VerificationDecision.REJECTED, reason, clock(5)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> report.decide(OFFICER, VerificationDecision.VERIFIED, null, clock(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.UNDER_REVIEW);
        assertThat(report.getVerification()).isNull();
        assertThat(report.getHistory()).hasSize(events);
        assertThat(report.getUpdatedAt()).isEqualTo(RECEIVED.plusSeconds(4));
    }

    @Test
    void failedChecklistItemsPreventVerificationButAllowRejection() {
        HazardReport report = underReview();
        report.updateReview(OFFICER, new CredibilityChecklist(true, false, true, true), null, clock(5));
        assertThatThrownBy(() -> report.decide(OFFICER, VerificationDecision.VERIFIED, null, clock(6)))
                .isInstanceOf(IllegalArgumentException.class);
        report.decide(OFFICER, VerificationDecision.REJECTED, "Photo is unrelated.", clock(6));
        assertThat(report.getStatus()).isEqualTo(ReportStatus.REJECTED);
    }

    @Test
    void nonDraftContentCannotBeChangedAndReviewRequiresSubmission() {
        HazardReport draft = draft();
        assertThatThrownBy(() -> draft.startReview(OFFICER, clock(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> draft.decide(OFFICER, VerificationDecision.REJECTED, "reason", clock(1)))
                .isInstanceOf(IllegalStateException.class);
        HazardReport report = underReview();
        assertThatThrownBy(() -> report.updateDraft(null, null, null, clock(5))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> report.replacePhoto(photo(), clock(5))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> report.removePhoto(clock(5))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> report.submit(clock(5))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> report.startReview(OFFICER, clock(5))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reviewingOfficerCannotBeChangedByAnotherIdentity() {
        HazardReport report = underReview();
        UserReference another = new UserReference("officer-2", "Another officer");
        assertThatThrownBy(() -> report.updateReview(another, PASSED, null, clock(5)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> report.decide(another, VerificationDecision.REJECTED, "reason", clock(5)))
                .isInstanceOf(IllegalStateException.class);
        // These are identity consistency checks, not HTTP authorization tests.
        assertThat(report.getReview().officer()).isEqualTo(OFFICER);
    }

    @ParameterizedTest
    @EnumSource(VerificationDecision.class)
    void finalDecisionsAreTerminal(VerificationDecision decision) {
        HazardReport report = underReview();
        report.updateReview(OFFICER, PASSED, null, clock(5));
        report.decide(OFFICER, decision, decision == VerificationDecision.REJECTED ? "reason" : null, clock(6));
        assertThatThrownBy(() -> report.decide(OFFICER, decision, "reason", clock(7)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> report.updateReview(OFFICER, PASSED, null, clock(7)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void draftEvidenceCanBeRemovedAndReplacedButMustMatchReporter() {
        HazardReport report = completeDraft();
        report.removePhoto(clock(3));
        assertThat(report.getPhoto()).isNull();
        report.replacePhoto(photo(), clock(4));
        PhotoEvidence other = new PhotoEvidence("other", "private/other", "other.png", "image/png", 1,
                "b".repeat(64), OFFICER, RECEIVED, null);
        assertThatThrownBy(() -> report.replacePhoto(other, clock(5))).isInstanceOf(IllegalArgumentException.class);
        assertThat(report.getPhoto()).isEqualTo(photo());
    }

    @Test
    void serverEventsCannotGoBackwards() {
        HazardReport report = completeDraft();
        assertThatThrownBy(() -> report.submit(clock(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.DRAFT);
    }

    @Test
    void metadataAndIdentityRejectInvalidValues() {
        assertThatThrownBy(() -> new UserReference(" ", "Name")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReporterIdentity(CITIZEN, null)).isInstanceOf(NullPointerException.class);
        for (String filename : new String[] {"", "../a.jpg", "dir\\a.jpg", "a\nb.jpg"}) {
            assertThatThrownBy(() -> new PhotoEvidence("id", "key", filename, "image/jpeg", 1,
                    "a".repeat(64), CITIZEN, RECEIVED, null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PhotoEvidence("id", "key", "a.jpg", "text/plain", 1,
                "a".repeat(64), CITIZEN, RECEIVED, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhotoEvidence("id", "key", "a.jpg", "image/jpeg", 0,
                "a".repeat(64), CITIZEN, RECEIVED, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhotoEvidence("id", "key", "a.jpg", "image/jpeg", 1,
                "invalid", CITIZEN, RECEIVED, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
