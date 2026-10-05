package com.dmc.backend.reporting;

import com.dmc.backend.models.hazard.PhotoEvidence;
import com.dmc.backend.models.hazard.UserReference;
import java.time.Instant;
import org.springframework.web.multipart.MultipartFile;

public interface EvidenceStorage {
    PhotoEvidence store(MultipartFile file, UserReference owner, Instant capturedAt, Instant uploadedAt);
    byte[] read(PhotoEvidence photo);
    /** Used only when a failed optimistic save proves this new attachment was not accepted. */
    void discardUnattached(PhotoEvidence photo);
}
