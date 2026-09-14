# BR34 — Persistence, Settings and Migration Seal

Overlay 14 of 16 in the BKX BR21–BR36 deduped remediation roadmap.

## Purpose

BR34 hardens persisted control-plane state after the direct Android, CLI, repository, transfer, jobs, scheduler, and run-history owners have been repaired. It targets U26: stores, config roots, migrations, settings and persistence contracts.

## Findings covered

- A01-21 — startup scheduling and backend runtime used different shared-state availability rules.
- A13-09 — dispatch-store read/modify/write could lose pending dispatches.
- A16-38 — operation feedback was not request-scoped.
- A17-04 — Android created the shared-state marker non-atomically and did not validate compatible schema versions.
- A17-16 — Android/Termux version skew could silently strip future fields or reject current Go schema.
- A17-19 — atomic-file durability was inconsistent across stores.
- A17-20 — embedded request/result publication fell back to direct overwrite.
- C01-06/C01-12/C01-13/C01-14 — YAML/config error ordering/path/directory contracts.
- C09-10 — rclone configured truth signal was only a file-exists/size check.

## Implementation summary

- Introduced a shared Go `statepaths.AtomicWriteFile` and moved JSON/JSONL stores onto one sibling-temp, fsynced, rename-published file path.
- Upgraded shared-state marker metadata to schema version 2 while continuing to accept schema version 1 markers.
- Added marker validation and invalid-marker quarantine before recovery; future marker versions fail closed.
- Added deterministic state-root resolution metadata and document listings in the shared marker.
- Added legacy schedule-store seeding and missing-schema migration so schedules join jobs, repositories and run history in shared-state convergence.
- Made config loading deterministic: sorted file keys, sorted environment variables, deterministic unknown-key diagnostics, explicit error for config paths that are directories, and `~` expansion using the supplied HOME.
- Changed rclone `Configured()` to require a parsable, safe remote section rather than merely a non-empty config file.
- Added Android `BkxAtomicFilePublisher` and removed direct-overwrite fallback from embedded request/result/cancel publication.
- Made Android shared-state marker creation atomic and schema-range validated.
- Aligned Android scheduler job reads with the current schema-2 saved-job store document.
- Serialized automation dispatch read/modify/write operations under one process lock.
- Versioned Android settings JSON and added request-scoped operation feedback alongside the existing latest-per-destination surface map.

## Validation added

Future gate task:

```text
:app:verifyBkxPersistenceSettingsMigrationSeal
```

Focused Go checks used during overlay creation:

```bash
go test ./internal/statepaths ./internal/config ./internal/schedulestore ./internal/rcloneops
go test ./internal/jobstore ./internal/repositorystore ./internal/executioncontrol ./internal/runstore
```

Full Android/Gradle validation is intentionally deferred to a later gate overlay because BR34 is an intermediate remediation overlay.
