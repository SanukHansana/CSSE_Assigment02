package com.dmc.backend.reporting;

import com.dmc.backend.models.hazard.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dmc/ground-reports")
@PreAuthorize("hasRole('DUTY_OFFICER')")
public class OfficerReviewController {
    private final OfficerReviewService reviews;
    public OfficerReviewController(OfficerReviewService reviews) { this.reviews = reviews; }
    @GetMapping("/review-queue")
    public ResponseEntity<ReportContracts.PageResponse> queue(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q, @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) HazardType hazardType,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reviews.queue(jwt.getSubject(), q, status, hazardType, page, size));
    }
    @GetMapping("/{id}/review/details")
    public ResponseEntity<ReportContracts.ReportResponse> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return response(reviews.detail(jwt.getSubject(), id));
    }
    @PostMapping("/{id}/review/start")
    public ResponseEntity<ReportContracts.ReportResponse> start(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody ReportContracts.VersionRequest request) {
        return response(reviews.start(jwt.getSubject(), id, request.expectedVersion()));
    }
    @PatchMapping("/{id}/review")
    public ResponseEntity<ReportContracts.ReportResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody ReviewContracts.UpdateReviewRequest request) {
        return response(reviews.update(jwt.getSubject(), id, request));
    }
    @PostMapping("/{id}/decision")
    public ResponseEntity<ReportContracts.ReportResponse> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody ReviewContracts.DecisionRequest request) {
        return response(reviews.decide(jwt.getSubject(), id, request));
    }
    @GetMapping("/{id}/review/photo")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return photo(jwt, id, false); }
    @GetMapping("/{id}/review/photo/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return photo(jwt, id, true); }
    private ResponseEntity<byte[]> photo(Jwt jwt, String id, boolean download) {
        var content = reviews.photo(jwt.getSubject(), id);
        var disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(content.metadata().originalFilename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(content.metadata().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff").body(content.bytes());
    }
    private ResponseEntity<ReportContracts.ReportResponse> response(HazardReport report) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ReportContracts.ReportResponse.fromForReview(report));
    }
}
