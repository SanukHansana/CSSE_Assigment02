package com.dmc.backend.reporting;

import com.dmc.backend.models.hazard.HazardType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dmc/ground-reports")
@PreAuthorize("hasRole('DMC_OFFICER')")
public class VerifiedEvidenceController {
    private final VerifiedEvidenceService evidence;
    public VerifiedEvidenceController(VerifiedEvidenceService evidence) { this.evidence = evidence; }
    @GetMapping("/verified-evidence")
    public ResponseEntity<List<ReportContracts.ReportResponse>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) HazardType hazardType, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evidence.list(jwt.getSubject(), hazardType, page, size));
    }
    @GetMapping("/verified-evidence/{id}")
    public ResponseEntity<ReportContracts.ReportResponse> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ReportContracts.ReportResponse.fromForAssessment(evidence.detail(jwt.getSubject(), id)));
    }
    @GetMapping("/{id}/assessment/photo")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return photo(jwt, id, false); }
    @GetMapping("/{id}/assessment/photo/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return photo(jwt, id, true); }
    private ResponseEntity<byte[]> photo(Jwt jwt, String id, boolean download) {
        var content = evidence.photo(jwt.getSubject(), id);
        var disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(content.metadata().originalFilename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(content.metadata().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff").body(content.bytes());
    }
}
