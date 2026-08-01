package com.hawkins.gallery.review.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import com.hawkins.gallery.review.domain.*;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {
    Optional<ProcessingJob> findByAssetIdAndJobType(String assetId, JobType jobType);
    long countByStatus(JobStatus status);

    @Query("""
        select j from ProcessingJob j
        where j.status = com.hawkins.gallery.review.domain.JobStatus.PENDING
          and j.jobType = :type
          and j.availableAt <= :now
        order by j.priority asc, j.createdAt asc
        """)
    List<ProcessingJob> next(@Param("type") JobType type, @Param("now") Instant now, Pageable pageable);
}
