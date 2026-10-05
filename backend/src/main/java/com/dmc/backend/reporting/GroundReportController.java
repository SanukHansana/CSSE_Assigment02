package com.dmc.backend.reporting;

import com.dmc.backend.models.hazard.ReportStatus;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/dmc/ground-reports")
@PreAuthorize("hasAnyRole('CITIZEN', 'COMMUNITY_VOLUNTEER')")
public class GroundReportController {
    private final GroundReportService reports;
    public GroundReportController(GroundReportService reports) { this.reports = reports; }

    @PostMapping("/drafts")
    public ResponseEntity<ReportContracts.ReportResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReportContracts.CreateDraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ReportContracts.ReportResponse.from(reports.create(jwt.getSubject(), request)));
    }
    @GetMapping("/mine")
    public ResponseEntity<ReportContracts.PageResponse> mine(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) ReportStatus status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reports.mine(jwt.getSubject(), page, size, status));
    }
    @GetMapping("/{id}")
    public ResponseEntity<ReportContracts.ReportResponse> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return response(reports.detail(jwt.getSubject(), id));
    }
    @PatchMapping("/{id}/draft")
    public ResponseEntity<ReportContracts.ReportResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody ReportContracts.UpdateDraftRequest request) {
        return response(reports.update(jwt.getSubject(), id, request));
    }
    @PutMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReportContracts.ReportResponse> attach(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestHeader("If-Match") String expectedVersion, @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Instant capturedAt) {
        return response(reports.attach(jwt.getSubject(), id, version(expectedVersion), file, capturedAt));
    }
    @DeleteMapping("/{id}/photo")
    public ResponseEntity<ReportContracts.ReportResponse> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestHeader("If-Match") String expectedVersion) {
        return response(reports.remove(jwt.getSubject(), id, version(expectedVersion)));
    }
    @PostMapping("/{id}/submit")
    public ResponseEntity<ReportContracts.ReportResponse> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody ReportContracts.VersionRequest request) {
        return response(reports.submit(jwt.getSubject(), id, request.expectedVersion()));
    }
    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> view(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return photo(jwt, id, false);
    }
    @GetMapping("/{id}/photo/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return photo(jwt, id, true);
    }
    private ResponseEntity<byte[]> photo(Jwt jwt, String id, boolean download) {
        var content = reports.photo(jwt.getSubject(), id);
        var disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(content.metadata().originalFilename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(content.metadata().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff").body(content.bytes());
    }
    private ResponseEntity<ReportContracts.ReportResponse> response(com.dmc.backend.models.hazard.HazardReport report) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ReportContracts.ReportResponse.from(report));
    }
    private long version(String value) {
        if (!value.matches("\"[0-9]+\"")) throw invalidVersion();
        try { return Long.parseLong(value.substring(1, value.length() - 1)); }
        catch (NumberFormatException exception) { throw invalidVersion(); }
    }
    private ReportException invalidVersion() {
        return new ReportException(HttpStatus.BAD_REQUEST, "INVALID_VERSION", "If-Match must contain a quoted report version.");
    }
}
