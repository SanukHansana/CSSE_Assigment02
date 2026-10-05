package com.dmc.backend.reporting;

import com.dmc.backend.auth.*;
import com.dmc.backend.models.hazard.*;
import java.time.*;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

final class ReportFixtures {
    static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MappingMongoConverter CONVERTER = converter();
    static UserAccount account(String id, AccountRole role) {
        return new UserAccount(id, id + "@example.com", "Reporter " + id, "private-hash", Set.of(role), true, NOW);
    }
    static HazardReport draft() {
        return version(HazardReport.draft(new ReporterIdentity(new UserReference("citizen", "Reporter citizen"),
                ReporterType.CITIZEN), ReportSource.WEB_PORTAL, NOW.minusSeconds(60), CLOCK), 0);
    }
    static HazardReport complete() {
        var report = draft();
        report.updateDraft(HazardType.FLOODING, "Water covers the road.", new ReportedLocation(0, 0, "District", NOW), CLOCK);
        report.replacePhoto(photo(), CLOCK);
        return report;
    }
    static PhotoEvidence photo() {
        String key = "f38b6015-041a-4efb-8b8b-10e1852d316a";
        return new PhotoEvidence(key, key, "road.png", "image/png", 3, "a".repeat(64),
                new UserReference("citizen", "Reporter citizen"), NOW, null);
    }
    static HazardReport version(HazardReport report, long value) {
        Document bson = new Document(); CONVERTER.write(report, bson); bson.put("version", value);
        return CONVERTER.read(HazardReport.class, bson);
    }
    static HazardReport copy(HazardReport report) { return version(report, report.getVersion()); }
    private static MappingMongoConverter converter() {
        try {
            var conversions = MongoCustomConversions.create(adapter -> { });
            var context = new MongoMappingContext();
            context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
            context.setInitialEntitySet(Set.of(HazardReport.class)); context.afterPropertiesSet();
            var mapper = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
            mapper.setCustomConversions(conversions); mapper.afterPropertiesSet(); return mapper;
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
