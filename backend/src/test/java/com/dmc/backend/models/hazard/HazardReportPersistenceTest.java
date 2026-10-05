package com.dmc.backend.models.hazard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.Date;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.event.ValidatingEntityCallback;

/** Real BSON mapping and save callback tests; do not require or claim a live database. */
class HazardReportPersistenceTest {
    private MappingMongoConverter converter;
    private MongoMappingContext context;

    @BeforeEach
    void configureMapper() throws Exception {
        MongoCustomConversions conversions = MongoCustomConversions.create(adapter -> { });
        context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.setInitialEntitySet(Set.of(HazardReport.class));
        context.afterPropertiesSet();
        converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
    }

    @Test
    void draftRoundTripsWithOptionalFieldsMissing() {
        HazardReport original = HazardReportTest.draft();
        Document bson = write(original);
        HazardReport loaded = converter.read(HazardReport.class, bson);
        assertThat(loaded.getId()).isEqualTo(original.getId());
        assertThat(loaded.getReporter()).isEqualTo(original.getReporter());
        assertThat(loaded.getClientCapturedAt()).isEqualTo(original.getClientCapturedAt());
        assertThat(loaded.isStateConsistent()).isTrue();
        assertThat(loaded.getPhoto()).isNull();
        assertThat(loaded.getVersion()).isNull();
        // A hydrated draft still supports controlled mutation.
        loaded.updateDraft(null, "New information", null, HazardReportTest.clock(1));
        assertThat(loaded.getHistory()).hasSize(2);
    }

    @Test
    void verifiedAggregateRoundTripsAllEmbeddedRecordsAndUtcDates() {
        HazardReport original = HazardReportTest.underReview();
        original.updateReview(HazardReportTest.OFFICER, HazardReportTest.PASSED, "Credible", HazardReportTest.clock(5));
        original.decide(HazardReportTest.OFFICER, VerificationDecision.VERIFIED, null, HazardReportTest.clock(6));
        Document bson = write(original);
        assertThat(bson.get("createdAt")).isInstanceOf(Date.class);
        assertThat(bson.get("clientCapturedAt")).isEqualTo(Date.from(HazardReportTest.CAPTURED));
        assertThat(bson.get("photo")).isInstanceOf(Document.class);
        assertThat(bson.get("verification")).isInstanceOf(Document.class);
        HazardReport loaded = converter.read(HazardReport.class, bson);
        assertThat(loaded.getVerification()).isEqualTo(original.getVerification());
        assertThat(loaded.getReview()).isEqualTo(original.getReview());
        assertThat(loaded.getLocation()).isEqualTo(original.getLocation());
        assertThat(loaded.getPhoto()).isEqualTo(original.getPhoto());
        assertThat(loaded.getHistory()).isEqualTo(original.getHistory());
        assertThat(loaded.isStateConsistent()).isTrue();
        assertThat(loaded.isEligibleForAssessment()).isTrue();
    }

    @Test
    void mappingMetadataIncludesVersionForLaterCompareAndSave() {
        var entity = context.getRequiredPersistentEntity(HazardReport.class);
        assertThat(entity.getCollection()).isEqualTo("hazard_reports");
        assertThat(entity.hasVersionProperty()).isTrue();
        assertThat(entity.getRequiredVersionProperty().getName()).isEqualTo("version");
        Document bson = write(HazardReportTest.draft());
        bson.put("version", 4L);
        assertThat(converter.read(HazardReport.class, bson).getVersion()).isEqualTo(4L);
        // This proves mapping, not that a database has rejected a concurrent write.
    }

    @Test
    void persistenceValidationRejectsTamperedStateAndOversizedDescription() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            ValidatingEntityCallback callback = new ValidatingEntityCallback(factory.getValidator());
            HazardReport draft = HazardReportTest.draft();
            assertThat(callback.onBeforeSave(draft, write(draft), "hazard_reports")).isSameAs(draft);
            Document invalidState = write(draft);
            invalidState.put("status", "VERIFIED");
            HazardReport malformed = converter.read(HazardReport.class, invalidState);
            assertThatThrownBy(() -> callback.onBeforeSave(malformed, invalidState, "hazard_reports"))
                    .isInstanceOf(ConstraintViolationException.class);
            Document invalidDescription = write(draft);
            invalidDescription.put("description", "x".repeat(501));
            HazardReport oversized = converter.read(HazardReport.class, invalidDescription);
            assertThatThrownBy(() -> callback.onBeforeSave(oversized, invalidDescription, "hazard_reports"))
                    .isInstanceOf(ConstraintViolationException.class);
        }
    }

    @Test
    void persistenceValidationRejectsDecisionMismatchAndBackwardsTimeline() {
        HazardReport original = HazardReportTest.underReview();
        original.decide(HazardReportTest.OFFICER, VerificationDecision.REJECTED, "Not credible", HazardReportTest.clock(5));
        Document mismatch = write(original);
        mismatch.put("status", "VERIFIED");
        assertThat(converter.read(HazardReport.class, mismatch).isStateConsistent()).isFalse();
        Document backwards = write(original);
        backwards.put("updatedAt", Date.from(HazardReportTest.RECEIVED.minusSeconds(1)));
        assertThat(converter.read(HazardReport.class, backwards).isStateConsistent()).isFalse();
    }

    @Test
    void timelineMustMatchLifecycleActionsAndTheirServerTimes() {
        HazardReport original = HazardReportTest.underReview();
        Document invalidAction = write(original);
        invalidAction.getList("history", Document.class).getLast().put("type", "DRAFT_UPDATED");
        assertThat(converter.read(HazardReport.class, invalidAction).isStateConsistent()).isFalse();
        Document invalidReceiptTime = write(original);
        invalidReceiptTime.put("submittedAt", Date.from(HazardReportTest.RECEIVED.plusSeconds(2)));
        assertThat(converter.read(HazardReport.class, invalidReceiptTime).isStateConsistent()).isFalse();
    }

    private Document write(HazardReport report) {
        Document bson = new Document();
        converter.write(report, bson);
        return bson;
    }
}
