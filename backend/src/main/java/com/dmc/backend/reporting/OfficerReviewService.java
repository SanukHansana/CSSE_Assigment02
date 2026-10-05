package com.dmc.backend.reporting;

import com.dmc.backend.models.hazard.HazardReport.UserReference;
import static org.springframework.http.HttpStatus.*;

import com.dmc.backend.auth.*;
import com.dmc.backend.models.hazard.*;
import com.dmc.backend.repository.HazardReportRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Review decisions/status/history are embedded in one version-protected MongoDB document. */
@Service
public class OfficerReviewService {
    private final HazardReportRepository reports;
    private final AuthService auth;
    private final MongoTemplate mongo;
    private final EvidenceStorage evidence;
    private final Clock clock;
    public OfficerReviewService(HazardReportRepository reports, AuthService auth, MongoTemplate mongo,
            EvidenceStorage evidence, Clock clock) {
        this.reports = reports; this.auth = auth; this.mongo = mongo; this.evidence = evidence; this.clock = clock;
    }

    public ReportContracts.PageResponse queue(String subject, String search, ReportStatus status,
            HazardType hazardType, int page, int size) {
        officer(subject);
        if (page < 0 || size < 1 || size > 100) throw bad("Use page >= 0 and size between 1 and 100.");
        ReportStatus selected = status == null ? ReportStatus.SUBMITTED : status;
        if (selected == ReportStatus.DRAFT) throw bad("Private drafts are not part of the review queue.");
        if (search != null && search.length() > 100) throw bad("Search must not exceed 100 characters.");
        Criteria criteria = Criteria.where("status").is(selected);
        if (hazardType != null) criteria = criteria.and("hazardType").is(hazardType);
        if (search != null && !search.isBlank()) {
            Pattern literal = Pattern.compile(Pattern.quote(search.strip()), Pattern.CASE_INSENSITIVE);
            criteria = criteria.andOperator(new Criteria().orOperator(Criteria.where("reference").regex(literal),
                    Criteria.where("reporter.user.displayName").regex(literal), Criteria.where("description").regex(literal)));
        }
        long total = mongo.count(Query.query(criteria), HazardReport.class);
        Query query = Query.query(criteria).with(Sort.by(Sort.Order.asc("submittedAt"), Sort.Order.asc("_id")))
                .skip((long) page * size).limit(size);
        List<ReportContracts.ReportResponse> items = mongo.find(query, HazardReport.class).stream()
                .map(ReportContracts.ReportResponse::fromForReview).toList();
        return new ReportContracts.PageResponse(items, page, size, total, (int) Math.ceil((double) total / size));
    }
    public HazardReport detail(String subject, String id) {
        officer(subject); return reviewable(id);
    }
    public GroundReportService.PhotoContent photo(String subject, String id) {
        HazardReport report = detail(subject, id);
        if (report.getPhoto() == null) throw new ReportException(NOT_FOUND, "PHOTO_NOT_FOUND", "No photo is attached.");
        return new GroundReportService.PhotoContent(report.getPhoto(), evidence.read(report.getPhoto()));
    }
    public HazardReport start(String subject, String id, long version) {
        UserAccount account = officer(subject);
        HazardReport report = versioned(id, version);
        if (report.getStatus() != ReportStatus.SUBMITTED) throw conflict("Only submitted reports can begin review.");
        report.startReview(reference(account), clock);
        return reports.save(report);
    }
    public HazardReport update(String subject, String id, ReviewContracts.UpdateReviewRequest request) {
        UserAccount account = officer(subject);
        HazardReport report = assigned(id, request.expectedVersion(), account);
        report.updateReview(reference(account), request.checklist(), request.comments(), clock);
        return reports.save(report);
    }
    public HazardReport decide(String subject, String id, ReviewContracts.DecisionRequest request) {
        UserAccount account = officer(subject);
        HazardReport report = assigned(id, request.expectedVersion(), account);
        CredibilityChecklist checklist = request.checklist() == null ? report.getReview().checklist() : request.checklist();
        String comments = request.comments() == null ? report.getReview().comments() : request.comments();
        if (request.decision() == VerificationDecision.VERIFIED && !checklist.allPassed()) {
            throw validation("checklist", "All four credibility checks must pass before verification.");
        }
        if (request.decision() == VerificationDecision.REJECTED
                && (request.rejectionReason() == null || request.rejectionReason().isBlank())) {
            throw validation("rejectionReason", "Provide a reason for rejection.");
        }
        if (request.decision() == VerificationDecision.VERIFIED && request.rejectionReason() != null) {
            throw validation("rejectionReason", "A verified report cannot have a rejection reason.");
        }
        // A failed save discards this detached working object. Never return it as persisted success.
        report.updateReview(reference(account), checklist, comments, clock);
        report.decide(reference(account), request.decision(), request.rejectionReason(), clock);
        return reports.save(report);
    }
    private HazardReport assigned(String id, Long version, UserAccount officer) {
        HazardReport report = versioned(id, version);
        if (report.getStatus() != ReportStatus.UNDER_REVIEW) throw conflict("The report must be under review before a decision.");
        if (!report.getReview().officer().subjectId().equals(officer.id())) {
            throw new ReportException(CONFLICT, "REVIEW_ASSIGNED_TO_ANOTHER_OFFICER", "Another officer owns this review.");
        }
        return report;
    }
    private HazardReport versioned(String id, Long expected) {
        HazardReport report = reviewable(id);
        if (expected == null || expected < 0) throw bad("Supply a nonnegative expected version.");
        if (!Objects.equals(report.getVersion(), expected)) throw new ReportException(CONFLICT,
                "REPORT_VERSION_CONFLICT", "The report changed. Reload it before trying again.");
        return report;
    }
    private HazardReport reviewable(String id) {
        return reports.findById(id).filter(report -> report.getStatus() != ReportStatus.DRAFT)
                .orElseThrow(() -> new ReportException(NOT_FOUND, "REPORT_NOT_FOUND", "Report not found."));
    }
    private UserAccount officer(String subject) {
        UserAccount user = auth.requireActiveAccount(subject);
        if (!user.roles().contains(AccountRole.DUTY_OFFICER)) {
            throw new ReportException(FORBIDDEN, "DUTY_OFFICER_REQUIRED", "Duty Officer access is required.");
        }
        return user;
    }
    private UserReference reference(UserAccount account) { return new UserReference(account.id(), account.displayName()); }
    private ReportException bad(String message) { return new ReportException(BAD_REQUEST, "INVALID_REQUEST", message); }
    private ReportException conflict(String message) { return new ReportException(CONFLICT, "INVALID_REVIEW_STATE", message); }
    private ReportException validation(String field, String message) {
        return new ReportException(UNPROCESSABLE_ENTITY, "REVIEW_VALIDATION_FAILED", message,
                List.of(new ReportException.FieldError(field, "invalid", message)));
    }
}
