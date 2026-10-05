package com.dmc.backend.reporting;

import static org.springframework.http.HttpStatus.*;
import com.dmc.backend.auth.*;
import com.dmc.backend.models.hazard.*;
import com.dmc.backend.repository.HazardReportRepository;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

/** Read-only official assessment boundary; never creates assessments or alters warnings. */
@Service
public class VerifiedEvidenceService {
    private final AuthService auth;
    private final HazardReportRepository reports;
    private final EvidenceStorage evidence;
    private final MongoTemplate mongo;
    public VerifiedEvidenceService(AuthService auth, HazardReportRepository reports, EvidenceStorage evidence, MongoTemplate mongo) {
        this.auth = auth; this.reports = reports; this.evidence = evidence; this.mongo = mongo;
    }
    public List<ReportContracts.ReportResponse> list(String subject, HazardType hazardType, int page, int size) {
        authorize(subject);
        if (page < 0 || size < 1 || size > 100) throw new ReportException(BAD_REQUEST, "INVALID_PAGINATION", "Use page >= 0 and size between 1 and 100.");
        Criteria criteria = Criteria.where("status").is(ReportStatus.VERIFIED).and("verification.decision").is(VerificationDecision.VERIFIED);
        if (hazardType != null) criteria = criteria.and("hazardType").is(hazardType);
        Query query = Query.query(criteria).with(Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.asc("_id")))
                .skip((long) page * size).limit(size);
        return mongo.find(query, HazardReport.class).stream().filter(HazardReport::isEligibleForAssessment)
                .map(ReportContracts.ReportResponse::fromForAssessment).toList();
    }
    public HazardReport detail(String subject, String id) {
        authorize(subject);
        return reports.findById(id).filter(HazardReport::isEligibleForAssessment).orElseThrow(() ->
                new ReportException(NOT_FOUND, "VERIFIED_EVIDENCE_NOT_FOUND", "Verified evidence not found."));
    }
    public GroundReportService.PhotoContent photo(String subject, String id) {
        HazardReport report = detail(subject, id);
        return new GroundReportService.PhotoContent(report.getPhoto(), evidence.read(report.getPhoto()));
    }
    private void authorize(String subject) {
        if (!auth.requireActiveAccount(subject).roles().contains(AccountRole.DMC_OFFICER))
            throw new ReportException(FORBIDDEN, "DMC_OFFICER_REQUIRED", "DMC Officer access is required.");
    }
}
