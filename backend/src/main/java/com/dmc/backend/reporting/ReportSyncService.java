package com.dmc.backend.reporting;

import static org.springframework.http.HttpStatus.*;
import com.dmc.backend.auth.*;
import com.dmc.backend.models.hazard.*;
import com.dmc.backend.models.hazard.HazardReport.*;
import com.dmc.backend.repository.HazardReportRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Idempotent completed-report reception, scoped to the authenticated reporter. */
@Service
public class ReportSyncService {
    public record SyncRequest(@NotNull HazardType hazardType, @NotBlank @Size(max = 500) String description,
            @NotNull @Valid ReportContracts.LocationRequest location, Instant clientCapturedAt,
            Instant photoCapturedAt, ReportSource source) { }
    private final AuthService auth;
    private final HazardReportRepository reports;
    private final EvidenceStorage evidence;
    private final Clock clock;
    public ReportSyncService(AuthService auth, HazardReportRepository reports, EvidenceStorage evidence, Clock clock) {
        this.auth = auth; this.reports = reports; this.evidence = evidence; this.clock = clock;
    }
    public HazardReport synchronize(String subject, String key, SyncRequest request, MultipartFile file) {
        UserAccount account = auth.requireActiveAccount(subject);
        if (!account.roles().contains(AccountRole.CITIZEN) && !account.roles().contains(AccountRole.COMMUNITY_VOLUNTEER))
            throw new ReportException(FORBIDDEN, "REPORTER_ROLE_REQUIRED", "A citizen or volunteer account is required.");
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,128}"))
            throw new ReportException(BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Use a 1–128 character submission key with letters, digits, dots, underscores or hyphens.");
        byte[] bytes = photoBytes(file);
        ReportSource source = request.source() == null ? ReportSource.WEB_PORTAL : request.source();
        String id = "SYNC-" + hash(encode(account.id(), key));
        String digest = hash(payload(request, source, file, bytes));
        var existing = reports.findByIdAndReporterUserSubjectId(id, account.id());
        if (existing.isPresent()) return repeated(existing.get(), digest);
        ReporterType type = account.roles().contains(AccountRole.COMMUNITY_VOLUNTEER)
                ? ReporterType.COMMUNITY_VOLUNTEER : ReporterType.CITIZEN;
        HazardReport report = HazardReport.synchronizedDraft(new ReporterIdentity(new UserReference(account.id(),
                account.displayName()), type), source, request.clientCapturedAt(), id, digest, clock);
        report.updateDraft(request.hazardType(), request.description(), request.location().toModel(), clock);
        PhotoEvidence photo = evidence.store(file, report.getReporter().user(), request.photoCapturedAt(), clock.instant());
        report.replacePhoto(photo, clock);
        report.submit(clock);
        try {
            return reports.save(report); // New @Version-null document inserts against unique _id.
        } catch (DuplicateKeyException exception) {
            evidence.discardUnattached(photo); // A competing request won the unique document ID.
            return reports.findByIdAndReporterUserSubjectId(id, account.id()).map(value -> repeated(value, digest))
                    .orElseThrow(() -> new ReportException(SERVICE_UNAVAILABLE, "SYNC_UNAVAILABLE", "Refresh and retry synchronization later."));
        }
        // An unknown write outcome keeps the file. Retrying the key resolves against the persisted record.
    }
    private HazardReport repeated(HazardReport report, String digest) {
        if (!Objects.equals(report.getSyncDigest(), digest))
            throw new ReportException(CONFLICT, "IDEMPOTENCY_KEY_REUSED", "This key belongs to a different submission. Use the original payload or a new key.");
        if (report.getStatus() == ReportStatus.DRAFT || !report.isStateConsistent())
            throw new ReportException(SERVICE_UNAVAILABLE, "SYNC_UNAVAILABLE", "The submission is not ready. Retry later.");
        return report; // Original report identity, with its current persisted status/version.
    }
    private byte[] photoBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ReportException(UNPROCESSABLE_ENTITY, "INVALID_PHOTO", "Attach a photo.");
        int max = 5 * 1024 * 1024;
        if (file.getSize() > max) throw new ReportException(PAYLOAD_TOO_LARGE, "PHOTO_TOO_LARGE", "Photo must not exceed 5 MiB.");
        try (InputStream stream = file.getInputStream()) {
            byte[] bytes = stream.readNBytes(max + 1);
            if (bytes.length > max) throw new ReportException(PAYLOAD_TOO_LARGE, "PHOTO_TOO_LARGE", "Photo must not exceed 5 MiB.");
            return bytes;
        } catch (IOException exception) {
            throw new ReportException(SERVICE_UNAVAILABLE, "EVIDENCE_UNAVAILABLE", "Photo storage is temporarily unavailable.");
        }
    }
    private byte[] payload(SyncRequest request, ReportSource source, MultipartFile file, byte[] photo) {
        var location = request.location();
        return encode(request.hazardType().name(), request.description(), source.name(),
                Double.toString(location.latitude()), Double.toString(location.longitude()), location.areaLabel(),
                timestamp(location.capturedAt()), timestamp(request.clientCapturedAt()), timestamp(request.photoCapturedAt()),
                file.getOriginalFilename(), file.getContentType(), hash(photo));
    }
    private String timestamp(Instant value) { return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS).toString(); }
    private byte[] encode(String... fields) {
        try {
            var output = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(output)) {
                data.writeInt(1); // Canonical payload version.
                for (String field : fields) {
                    if (field == null) data.writeInt(-1);
                    else { byte[] bytes = field.getBytes(StandardCharsets.UTF_8); data.writeInt(bytes.length); data.write(bytes); }
                }
            }
            return output.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
