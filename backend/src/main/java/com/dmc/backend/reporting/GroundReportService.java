package com.dmc.backend.reporting;

import static org.springframework.http.HttpStatus.*;

import com.dmc.backend.auth.*;
import com.dmc.backend.models.hazard.*;
import com.dmc.backend.repository.HazardReportRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Reporter-only Stage 2 operations. Every access is scoped to the current enabled account. */
@Service
public class GroundReportService {
    private final HazardReportRepository reports;
    private final AuthService auth;
    private final EvidenceStorage evidence;
    private final Clock clock;

    public GroundReportService(HazardReportRepository reports, AuthService auth, EvidenceStorage evidence, Clock clock) {
        this.reports = reports; this.auth = auth; this.evidence = evidence; this.clock = clock;
    }

    public HazardReport create(String subject, ReportContracts.CreateDraftRequest request) {
        UserAccount account = reporter(subject);
        ReporterType type = account.roles().contains(AccountRole.COMMUNITY_VOLUNTEER)
                ? ReporterType.COMMUNITY_VOLUNTEER : ReporterType.CITIZEN;
        HazardReport report = HazardReport.draft(new ReporterIdentity(
                new UserReference(account.id(), account.displayName()), type),
                request.source() == null ? ReportSource.WEB_PORTAL : request.source(), request.clientCapturedAt(), clock);
        if (request.hazardType() != null || request.description() != null || request.location() != null) {
            report.updateDraft(request.hazardType(), request.description(),
                    request.location() == null ? null : request.location().toModel(), clock);
        }
        return reports.save(report);
    }

    public HazardReport detail(String subject, String id) { return owned(subject, id); }

    public ReportContracts.PageResponse mine(String subject, int page, int size, ReportStatus status) {
        UserAccount account = reporter(subject);
        if (page < 0 || size < 1 || size > 100) throw new ReportException(BAD_REQUEST,
                "INVALID_PAGINATION", "Use page >= 0 and size between 1 and 100.");
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        var result = status == null ? reports.findByReporterUserSubjectId(account.id(), pageable)
                : reports.findByReporterUserSubjectIdAndStatus(account.id(), status, pageable);
        return new ReportContracts.PageResponse(result.getContent().stream()
                .map(ReportContracts.ReportResponse::from).toList(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    public HazardReport update(String subject, String id, ReportContracts.UpdateDraftRequest request) {
        HazardReport report = editable(subject, id, request.expectedVersion());
        report.updateDraft(request.hazardType(), request.description(),
                request.location() == null ? null : request.location().toModel(), clock);
        return reports.save(report);
    }

    public HazardReport attach(String subject, String id, long version, MultipartFile file, Instant capturedAt) {
        HazardReport report = editable(subject, id, version);
        PhotoEvidence photo = evidence.store(file, report.getReporter().user(), capturedAt,
                clock.instant());
        try {
            report.replacePhoto(photo, clock);
            return reports.save(report);
        } catch (OptimisticLockingFailureException | IllegalArgumentException | IllegalStateException exception) {
            evidence.discardUnattached(photo);
            throw exception;
        }
        // For an ambiguous database failure retain the new file: the save may have succeeded.
        // Replaced files also remain private until an explicit offline orphan cleanup is performed.
    }

    public HazardReport remove(String subject, String id, long version) {
        HazardReport report = editable(subject, id, version);
        report.removePhoto(clock);
        return reports.save(report);
    }

    public HazardReport submit(String subject, String id, long version) {
        HazardReport report = editable(subject, id, version);
        var missing = new ArrayList<ReportException.FieldError>();
        if (report.getHazardType() == null) missing.add(required("hazardType", "Select a hazard type."));
        if (report.getDescription() == null || report.getDescription().isBlank())
            missing.add(required("description", "Describe the observed hazard."));
        if (report.getLocation() == null) missing.add(required("location", "Provide valid GPS coordinates."));
        if (report.getPhoto() == null) missing.add(required("photo", "Attach a photo."));
        if (!missing.isEmpty()) throw new ReportException(UNPROCESSABLE_ENTITY, "REPORT_VALIDATION_FAILED",
                "Complete the required fields before submitting.", missing);
        evidence.read(report.getPhoto()); // Reject missing/corrupt files before changing submission state.
        report.submit(clock);
        return reports.save(report);
    }

    public PhotoContent photo(String subject, String id) {
        HazardReport report = owned(subject, id);
        if (report.getPhoto() == null) throw new ReportException(NOT_FOUND, "PHOTO_NOT_FOUND", "No photo is attached.");
        return new PhotoContent(report.getPhoto(), evidence.read(report.getPhoto()));
    }
    public record PhotoContent(PhotoEvidence metadata, byte[] bytes) { }

    private HazardReport editable(String subject, String id, Long version) {
        HazardReport report = owned(subject, id);
        if (version == null || version < 0) throw new ReportException(BAD_REQUEST,
                "INVALID_VERSION", "Supply a nonnegative expected version.");
        if (!Objects.equals(report.getVersion(), version)) throw new ReportException(CONFLICT,
                "REPORT_VERSION_CONFLICT", "The report changed. Reload it before trying again.");
        if (report.getStatus() != ReportStatus.DRAFT) throw new ReportException(CONFLICT,
                "REPORT_NOT_EDITABLE", "Only draft reports can be edited or submitted.");
        return report;
    }
    private HazardReport owned(String subject, String id) {
        UserAccount account = reporter(subject);
        return reports.findByIdAndReporterUserSubjectId(id, account.id()).orElseThrow(() ->
                new ReportException(NOT_FOUND, "REPORT_NOT_FOUND", "Report not found."));
    }
    private UserAccount reporter(String subject) {
        UserAccount account = auth.requireActiveAccount(subject);
        if (!account.roles().contains(AccountRole.CITIZEN) && !account.roles().contains(AccountRole.COMMUNITY_VOLUNTEER))
            throw new ReportException(FORBIDDEN, "REPORTER_ROLE_REQUIRED", "A citizen or volunteer account is required.");
        return account;
    }
    private static ReportException.FieldError required(String field, String message) {
        return new ReportException.FieldError(field, "required", message);
    }
}
