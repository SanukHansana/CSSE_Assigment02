package com.dmc.backend.reporting;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/dmc/ground-reports")
public class ReportSyncController {
    private final ReportSyncService sync;
    public ReportSyncController(ReportSyncService sync) { this.sync = sync; }
    @PostMapping(value = "/sync", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('CITIZEN', 'COMMUNITY_VOLUNTEER')")
    public ResponseEntity<ReportContracts.ReportResponse> synchronize(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestPart("report") ReportSyncService.SyncRequest request,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ReportContracts.ReportResponse.from(sync.synchronize(jwt.getSubject(), key, request, file)));
    }
}
