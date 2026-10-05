package com.dmc.backend.repository;

import com.dmc.backend.models.hazard.HazardReport;
import com.dmc.backend.models.hazard.ReportStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Internal persistence only; application services must enforce identity and permissions. */
public interface HazardReportRepository extends MongoRepository<HazardReport, String> {
    Optional<HazardReport> findByIdAndReporterUserSubjectId(String id, String subjectId);
    Page<HazardReport> findByReporterUserSubjectId(String subjectId, Pageable pageable);
    Page<HazardReport> findByReporterUserSubjectIdAndStatus(String subjectId, ReportStatus status, Pageable pageable);
    Page<HazardReport> findByStatus(ReportStatus status, Pageable pageable);
}
