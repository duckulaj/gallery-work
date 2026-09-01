package com.hawkins.gallery.review.repository;

import java.time.Instant;
import java.util.Collection;
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

    @Modifying
    @Query(value = """
        insert into processing_job(id, asset_id, job_type, status, priority, attempts, max_attempts,
                                   available_at, created_at, updated_at, version)
        select gen_random_uuid(), a.id, 'NSFW', 'PENDING', 40, 0, 3, now(), now(), now(), 0
          from assets a
         where (:allAssets or a.id in (:ids))
        on conflict (asset_id, job_type) do update set status='PENDING', priority=40, attempts=0,
          last_error=null, available_at=now(), started_at=null, completed_at=null, lease_until=null,
          worker_id=null, updated_at=now(), version=processing_job.version+1
        where :force or processing_job.status not in ('COMPLETED','RUNNING')
        """, nativeQuery = true)
    int enqueueNsfw(@Param("ids") Collection<String> ids, @Param("allAssets") boolean allAssets,
            @Param("force") boolean force);

    @Modifying
    @Query(value = "delete from processing_job where status='COMPLETED' and completed_at < :cutoff",
            nativeQuery = true)
    int deleteCompletedBefore(@Param("cutoff") Instant cutoff);
}
