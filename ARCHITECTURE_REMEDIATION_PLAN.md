# Gallery remediation proposal

Status: **proposal only — no application changes have been made**

This document translates the peer-review findings into reviewable implementation work. The work is deliberately divided into checkpoints so each group of source changes can be inspected and tested before the next begins.

## Decisions proposed

1. `asset_review` becomes the canonical NSFW result and review-state store.
2. `processing_job` becomes the canonical durable background-job mechanism.
3. Imported originals remain in place; only generated thumbnails/crops live under `app.storage-root`.
4. The application remains suitable for local use, but binds to loopback by default and still protects mutating HTTP endpoints with authentication and CSRF.
5. PostgreSQL with pgvector is mandatory; the obsolete non-pgvector fallback is removed.
6. All configured filesystem paths are absolute after configuration binding and validation.

These decisions are the few parts most likely to affect product behaviour. They should be approved before implementation.

## Checkpoint 1 — build hygiene and configuration

### Proposed edits

- Parent `pom.xml`
  - Configure Maven Surefire to load Mockito's Java agent on Java 25.
  - Add JaCoCo reporting and fail the build if tests fail.
  - Remove redundant explicit Spring Boot versions inherited from the parent.
- `.gitignore`
  - Add `**/__pycache__/`, `*.py[cod]`, `*.log.*`, and generated face crops.
- Repository
  - Remove the tracked `.pyc` and compressed runtime log from Git (not from unrelated user storage).
- `AppProperties`
  - Add `@Validated` plus range/nonblank constraints.
  - Bind paths as `Path`, not `String`.
  - Add one startup normalizer that expands a leading `~/` for backward compatibility, then stores absolute normalized paths.
- `application.yml`
  - Replace `~` paths with `${GALLERY_DATA_ROOT:${user.home}/Gallery-App/data}`-based values.
  - Bind HTTP to `${GALLERY_BIND_ADDRESS:127.0.0.1}` by default.
  - Disable Thymeleaf development settings outside a `dev` profile.
  - Make scheduler timezone explicit (`Europe/London` by default, configurable).

### Tests

- Configuration binding rejects invalid thresholds, negative thread counts, and blank URLs.
- Paths resolve beneath the expected data root.
- `mvn clean verify` passes on Java 25.

## Checkpoint 2 — schema and typed state

### Proposed migration: `V2__remediation_constraints_and_job_claiming.sql`

- Add a unique constraint on `folders.source_path` when non-null.
- Add unique indexes for `(folder_id, checksum)` and `(folder_id, storage_path)`.
- Add state check constraints for AI, review, NSFW, and processing-job statuses.
- Add `processing_job.lease_until` and `processing_job.worker_id`.
- Add `asset_review.operation_status` and `asset_review.operation_error` for durable file moves.
- Add a face-model/application version field so known-face changes do not require reprocessing every asset.
- Standardize Java `Instant` columns on `timestamp with time zone`.

The existing V1 baseline will not be edited: deployed databases must receive an additive V2 migration.

### Java model edits

- Map `AssetMetadata.aiStatus`, NSFW level, and review status through enums/converters.
- Add optimistic `@Version` columns to mutable queue/review records where useful.
- Move enum parsing/validation out of controllers.

### Tests

- Testcontainers PostgreSQL + pgvector migration and Hibernate schema validation.
- Constraint and cascade tests.
- Repository tests for full-text and vector search.

## Checkpoint 3 — atomic durable job processing

### Proposed design

`ProcessingJobRepository.claimNext(...)` will use a PostgreSQL native statement built around:

```sql
select id
from processing_job
where status = 'PENDING'
  and job_type = :type
  and available_at <= now()
order by priority, created_at
for update skip locked
limit 1
```

The locked row is changed to `RUNNING`, assigned a worker and lease, and returned in the same short transaction. Expired leases are eligible for recovery. Completion/failure updates require the expected worker ID, preventing a stale worker from completing a reassigned job.

### Proposed edits

- Replace the `asset_metadata` polling queue in `AiEnrichmentService` with `processing_job` jobs (`VISION`, `FACE`, `EMBEDDING`, or a single orchestrating `ENRICHMENT` type).
- Keep external AI work outside transactions.
- Persist each completed result in a short transaction.
- Use bounded executors with named non-daemon threads, explicit shutdown waiting, queue capacity, and rejection handling.
- Replace best-effort `CompletableFuture.cancel(true)` semantics with cooperative cancellation checked between stages.
- Recover expired `RUNNING` jobs at startup and periodically.
- Add an application-level scheduler lock or rely exclusively on atomic claims so multiple instances are safe.

### Tests

- Multiple workers cannot claim the same job.
- Expired leases are recovered.
- Retry backoff and maximum attempts are deterministic.
- Cancellation cannot be overwritten by late completion/failure.
- Restart during processing does not lose the job.

## Checkpoint 4 — consolidate NSFW processing

### Proposed edits

- Retain `gallery-review`'s `NsfwClient`, `NsfwJobProcessor`, `AssetReview`, and review UI.
- Remove the duplicate `gallery-ai` NSFW client and all NSFW fields/writes from `AssetMetadata` after data migration.
- Enqueue exactly one NSFW job from `AssetIndexedReviewListener`.
- Make detector model/scoring version part of the result and requeue only stale results.
- Consolidate the two quarantine roots into `app.review.quarantine-root`.
- Remove the legacy sensitive-review controller or redirect it to the canonical review controller without writing separate state.
- Validate thresholds within `[0,1]` and statuses against allowed transitions.

### Tests

- One indexed asset produces one NSFW job and one canonical result.
- Manual decisions survive detector reprocessing.
- Detector errors transition predictably and retain bounded diagnostics.

## Checkpoint 5 — reliable file operations

### Proposed quarantine state machine

1. In a short transaction, validate the asset and create a `MOVE_PENDING` operation containing source and destination.
2. Move the file outside the transaction.
3. In a second transaction, mark it `QUARANTINED` and update the asset's active storage path.
4. If step 2 fails, record `MOVE_FAILED` without changing the active path.
5. A reconciliation job repairs operations interrupted between steps 2 and 3 by checking both paths.

Restore uses the same mechanism in reverse. Batch requests return per-asset outcomes instead of rolling all items into one transaction.

### Other filesystem edits

- Delete thumbnails/crops only after database commit, through a cleanup job.
- Clean old face crops after successful replacement; retain them if persistence fails.
- Resolve import roots with `toRealPath()` and reject symlink escapes.
- Restrict imports to configured roots rather than the entire user home.
- Never use `REPLACE_EXISTING` for quarantine destinations.

### Tests

- Database failure after a move is reconciled.
- Filesystem failure does not claim success in the database.
- Name collisions and cross-filesystem moves are safe.
- Symlink traversal outside an import root is rejected.

## Checkpoint 6 — HTTP security and API validation

### Proposed edits

- Add Spring Security with a local administrator account sourced from environment variables or a generated startup credential.
- Require authentication for gallery files and UI pages.
- Require CSRF tokens for every state-changing browser request, including HTMX/JavaScript requests.
- Authorize destructive operations separately (`ROLE_ADMIN`).
- Keep actuator `health` available locally; require authorization for metrics and Prometheus.
- Add Bean Validation request DTOs and a `@ControllerAdvice` that returns consistent 400/404/409 responses.
- Apply `Content-Disposition: inline` and `X-Content-Type-Options: nosniff` to served images.
- Verify stored original, thumbnail, and crop paths against their configured real roots before serving.
- Remove exception details and absolute server paths from responses/UI where not necessary.

### Tests

- Anonymous mutations are rejected.
- CSRF is required.
- Invalid statuses, IDs, thresholds, and empty batches produce 400 responses.
- Files outside configured roots cannot be served.

## Checkpoint 7 — query and indexing performance

### Proposed edits

- Replace `ReviewService.cards()` with a joined projection query supporting database filtering, sorting, and `Pageable`.
- Replace in-memory counts with grouped/count repository queries.
- Ensure `ReviewController.populate()` performs one page query and one aggregate query, not two complete catalogue scans.
- Batch-load preview metadata and unidentified-face counts.
- Avoid recursively issuing one folder query per node; fetch folders once and assemble the tree in memory.
- Stream directory candidates rather than materializing an unbounded `List<Path>`.
- Use database uniqueness constraints as the final deduplication authority.

### Tests

- Repository paging/filter semantics.
- Query-count assertions for review and folder-tree rendering.
- Large synthetic catalogue smoke test.

## Checkpoint 8 — external-service hardening

### Java clients

- Centralize HTTP-client construction and lifecycle.
- Add connect, connection-request, and response timeouts to every client.
- Bound response bodies and classify retryable versus permanent failures.
- Add Micrometer timers/counters tagged by operation and outcome, not filename or asset ID.
- Use circuit breaking only if operational evidence shows it is needed.

### Python face service

- Stream uploads to bounded temporary files; reject oversized bodies before inference.
- Decode and validate image dimensions/pixel count to mitigate decompression bombs.
- Limit concurrent inference with a semaphore because models/GPU memory are shared.
- Return stable public error codes; log internal exception details server-side.
- Run blocking OpenCV/DeepFace/NudeNet work outside the FastAPI event-loop thread.
- Add readiness failure when required models are unavailable.
- Pin dependencies with hashes or a generated lock file and run as a non-root container user.

### Tests

- Oversized, malformed, truncated, and decompression-heavy images are rejected.
- Concurrent requests respect the configured limit.
- Java timeout/retry/error mapping is deterministic.

## Checkpoint 9 — module boundaries

This is intentionally last because it is a structural refactor, not required to correct the earlier defects.

- Move repository interfaces required by business services into an application/port package.
- Keep Spring Data implementations and JPA entities in persistence adapters.
- Separate API/view DTOs from persistence entities so lazy entities never reach templates/controllers.
- Define explicit ports for vision, embedding, face detection, NSFW detection, and file storage.
- Remove `gallery-domain`'s dependency on `spring-boot-starter-data-jpa` once entities move to persistence.
- Add ArchUnit rules for allowed module dependencies.

## Implementation order and review gates

| Gate | Work | Expected review artifact |
|---|---|---|
| 1 | Build/configuration hygiene | Passing Java 25 build and config diff |
| 2 | V2 schema and typed state | Migration + Testcontainers evidence |
| 3 | Atomic job queue | Concurrency tests and queue-service diff |
| 4 | NSFW consolidation | Removed duplicate pipeline + behavior tests |
| 5 | File-operation state machine | Failure/reconciliation tests |
| 6 | HTTP security | Security configuration + MVC tests |
| 7 | Query performance | Repository projections + query evidence |
| 8 | AI service hardening | Client/service tests and container changes |
| 9 | Boundary refactor | ArchUnit rules and dependency diagram |

Each gate should be implemented as a separately reviewable change. `mvn clean verify` and the Python test suite must pass before proceeding to the next gate.

## Definition of done

- A clean checkout builds and tests on the declared Java 25 runtime.
- Database initialization and upgrade both work on PostgreSQL with pgvector.
- No job is processed twice under concurrent workers.
- Restart, cancellation, and retry behavior are durable and tested.
- NSFW data has one canonical representation.
- File moves cannot silently diverge from database state.
- Filesystem access is confined to configured real roots.
- Mutating endpoints require authenticated authorization and CSRF protection.
- Review screens use bounded database queries.
- Generated files and logs are not tracked.
- Operational documentation explains required environment variables, backups, quarantine recovery, and job recovery.

## Approval action

After reviewing this file, select **Continue** to authorize implementation. Unless you request another grouping, implementation will start with **Checkpoint 1 only**, stop after tests, and present that diff for review before Checkpoint 2.
