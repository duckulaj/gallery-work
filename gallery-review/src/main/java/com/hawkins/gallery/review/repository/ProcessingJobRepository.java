package com.hawkins.gallery.review.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import com.hawkins.gallery.review.domain.*;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {
    Optional<ProcessingJob> findByAssetIdAndJobType(String assetId, JobType jobType);
    long countByStatus(JobStatus status);

    @Query(value = """
        select * from processing_job
         where job_type = cast(:type as varchar)
           and ((status = 'PENDING' and available_at <= :now)
             or (status = 'RUNNING' and lease_until < :now))
         order by priority asc, created_at asc
         for update skip locked
         limit 1
        """, nativeQuery = true)
    Optional<ProcessingJob> lockNext(@Param("type") String type, @Param("now") Instant now);

    @Modifying
    @Query("""
        update ProcessingJob j set j.status = :status, j.completedAt = :completedAt,
            j.lastError = null, j.leaseUntil = null
         where j.id = :id and j.workerId = :workerId
           and j.status = com.hawkins.gallery.review.domain.JobStatus.RUNNING
        """)
    int completeOwned(@Param("id") UUID id, @Param("workerId") String workerId,
            @Param("status") JobStatus status, @Param("completedAt") Instant completedAt);
}
