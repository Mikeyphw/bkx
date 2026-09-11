# BKX BR19+BR20 — final backup, jobs, snapshot, Android, and Tasker seal

BR19+BR20 audits the BR01–BR18 roadmap as one cross-client product and closes the remaining parity/migration gaps before full validation.

## Delivered roadmap

| Stage | Delivered contract |
| --- | --- |
| BR01+02 | Versioned `OperationSpecV2`, revisioned Saved Jobs, atomic/locked persistence, secret-reference-only storage, V1 migration |
| BR03+04 | `bkx job` CRUD/plan/run/import/export/clone/create-from-run and historical `bkx run replay` |
| BR05+06 | Bakery/Restic backup-engine abstraction and local deduplicated Restic snapshots |
| BR07+08 | Named local/rclone/managed-Google-Drive repositories with credential isolation |
| BR09+10 | Snapshot list/show/diff/restore/forget, retention, repository check, and explicit prune |
| BR11+12 | Android Jobs and Repositories UI backed by the canonical Go stores and shared Android/Termux control plane |
| BR13+14 | Android snapshot browser, comparison, selective/whole restore, safe overwrite defaults, JSON progress, cancellation |
| BR15+16 | Tasker V2 Saved Job/repository/snapshot Actions plus lifecycle Events/States and backward-compatible variables |
| BR17+18 | Tasker execution overrides, shared leases/cancellation, schedules, Android JobScheduler, reboot/process-death recovery |
| BR19+20 | Cross-client run-history convergence, `latest~N`, complete CLI help, rich Tasker metrics, migration audit, final release gate |

## One non-secret control plane

Jobs, repository definitions, schedules, execution control, and run history resolve through `internal/statepaths`. Normal desktop/CLI use defaults to `~/.local/share/bkx`; `BKX_STATE_DIR` can override it. On the Android + Termux device lane, the Android shared-state marker causes the real Termux HOME to converge on `/storage/emulated/0/BKX/.state`.

Only non-secret control data belongs there. Restic passwords/passphrases, OAuth tokens, imported rclone secrets/configuration, Tasker capability tokens, and private signing material remain outside the shared state root.

Run-history migration is intentionally stronger than ordinary document seeding: unique records from a legacy run history are unioned into the canonical store by `run_id`, while legacy files remain intact. If the same `run_id` already exists canonically, the canonical record wins deterministically and the legacy source is not rewritten. This makes CLI-created runs visible to Android history/replay and Android-created runs visible to the CLI after both clients resolve the shared root.

## Replay and selectors

Saved Jobs remain the primary reusable-operation surface: execution-only overrides never mutate the stored revision. Historical replay uses the immutable captured `spec_snapshot` for supported recorded operations. Restic snapshot selectors support `latest`, `latest~0`, `latest~1`, and higher non-negative offsets, with out-of-range and malformed selectors failing closed.

## Backup semantics

Bakery remains the portable single-archive engine. Restic remains the incremental/deduplicated snapshot engine. Local repositories and rclone-backed repositories coexist; BKX-managed Google Drive is constrained to BKX's managed remote namespace and token refresh stays private to Android. Forget and prune remain separate. Retention/prune are previews unless explicitly approved, and restore defaults to `overwrite=never` and a separate restore target.

## Android and Tasker parity

Android `Backup` exposes Backup now, Jobs, Repositories, and Snapshots using shared bridge operations rather than parsing CLI output. Tasker V2 uses the same job/repository/snapshot identities and shared execution control. Existing Tasker actions/events/states and output aliases remain available. New result aliases include processed files/bytes, Restic added/reused bytes, duration, job ID, run ID, repository ID, and snapshot ID.

Schedules are shared definitions with a 15-minute minimum cadence. Network policy is `none`, `any`, or `unmetered`; `none` is the default so local jobs can run offline. Android JobScheduler reconciliation is idempotent and runs at startup, reboot, package replacement, and after CLI schedule mutation notification.

## Migration rules

- Existing Bakery archives are never converted to Restic implicitly.
- Legacy Saved Job V1 documents migrate to V2 without embedding credentials.
- Legacy HOME-scoped jobs/repositories are copied only when the canonical destination is absent; conflicting catalogs are not overwritten silently.
- Legacy run histories are union-migrated by unique `run_id` because append-only history can be reconciled safely.
- Old Tasker wire values/variables remain accepted; V2 additions are additive.
- The frozen migration-v1 bridge operation/capability contract is not rewritten; newer operations are additive service/bridge extensions.

## Final validation

BR19+BR20 is the first full-validation overlay in this roadmap series. The release gate runs the final roadmap contract together with Android unit tests, lint, debug/release compilation/packaging through `bkxAndroidReleaseSeal`, and the repository Go test suite from the artifact validator. Connected Android tests retain the existing Devtool no-device skip behavior and run when a usable device is present.

## v4 validation repair

The final Android seal now enforces the app's project-standard JUnit 4 test stack across
`app/src/test` and `app/src/androidTest`. Snapshot roadmap contracts no longer import
`kotlin.test`, preventing unit-test compilation from depending on an undeclared Kotlin test API.


## v4 Go and Android overlay sequence

The final work is delivered as two ordered overlays from the supplied post-BR18 snapshot
(commit `22b79355`):

1. `bkx_br19_go_zstd_core_v4.zip`, target `bkx`, applies the Go core, archive/zstd
   integrity repairs, shared run-history migration/recovery, CLI parity, and regression tests.
   Apply this intermediate overlay with `--no-validate`.
2. `bkx_br20_android_final_backup_automation_seal_v4.zip`, target `bkx_android`, adds
   the Android/Tasker final changes, the JUnit 4 repairs, the Android snapshot-forget
   selector guard, and the final cross-client audit. Apply with `--validate` and
   explicit Go-cutover/final-contract/unit/lint/release tasks.

The final artifact validator runs the full Go test/vet suite with the actual declared
`github.com/klauspost/compress` dependency, verifies the module cache, and rejects
replacement modules for that dependency. No test-only codec substitute is shipped.
A failed second overlay rolls back only its Android/final-seal changes: the successful
Go overlay remains the baseline for a subsequent Android fix.

## Additional integrity and recovery repairs

- Native zstd creation and restore work without an external `zstd` executable for
  TAR/Bakery and ZIP archives, including split archives and empty/binary files.
- ZIP zstd reading validates the ZIP CRC32 and declared uncompressed size through
  the standard checked ZIP reader; method 93 is written and legacy method 20 is readable.
- Empty ZIP members emit valid zstd frames so independent decoders can read them.
- TAR readers consume the enclosing compressed frame footer, detecting truncated
  streams and checksum failures before successful verification or restore publication.
- Snapshot forget refuses `latest` and every `latest~N` relative selector in Go and
  Android. An explicit immutable snapshot ID is required.
- Opening shared history checks active execution leases before marking unfinished runs
  interrupted, preserving a live operation while another client inspects history.
- Multipart cloud tests use reproducible incompressible data, and the frozen ZIP
  oracle pins its historical UTC fixture timezone without changing runtime semantics.

## Validation evidence and limits

The final source was tested with Go 1.27.1 and the genuine `klauspost/compress` v1.18.4,
including native zstd levels 1, 3, 10, and 22; independent system-zstd interoperability;
corrupt ZIP CRC/size rejection; truncated/corrupt zstd frame rejection; legacy ZIP
method 20; and local/remote split archives. The Android snapshot/model tests and
shared-history contract compile and run under Kotlin 2.4.10 / JUnit 4.13.2 with real
serialization dependencies. Gradle contract bodies are additionally compiled/executed
in a focused Kotlin harness; this does not substitute for Gradle task execution.

Full Android Gradle unit tests, lint, APK packaging, personal-signing validation, and
physical-device tests remain the authoritative Termux final-overlay gate. Local checks
do not claim a full Android Gradle pass or a device runtime test.

## v6 complete-roadmap parity closure

The v6 replacement was produced after auditing the original BR01-BR20 promise line-by-line against the post-BR19 source instead of relying on the earlier token-presence seal. It folds the v5 test-root/BridgeJson repair and closes the remaining behavior gaps in one final overlay:

- Run history now stores `job_id`, `job_revision`, the exact effective `OperationSpecV2`, and a durable lifecycle `phase`. Saved-job provenance is written atomically with run start, so process death cannot leave an interrupted run without the revision/spec that actually ran.
- User cancellation is durably `cancelled`; crash/reboot/process-death recovery remains `interrupted`. Canonical phases are `queued`, `preparing`, `running`, `finalizing`, `verifying`, `succeeded`, `failed`, `cancelled`, and `interrupted`.
- `runs.replay` is a shared service operation. CLI replay and Android **Run again** use that same pipeline, and Android Backup now exposes a History surface backed by canonical Go `runs.list`, with the previous private history retained only as cache/fallback.
- Canonical Android history decoding accepts both RFC3339 timestamps and BKX's legacy/local timezone-less ISO timestamps, preventing valid CLI records from disappearing during convergence.
- Android no longer places every foreground operation behind one process-global mutex. Shared job/repository execution leases remain authoritative, so the same job/repository is protected while unrelated repositories can execute concurrently.
- Restic operations retry once after the conservative `restic unlock` stale-lock cleanup. BKX never uses `unlock --remove-all`, and live BKX execution leases remain the first concurrency guard.
- Saved-job execution overrides are execution-copy-only. `source=/path` maps to the canonical `sources` list; `repository=` / `repository_ref=` and Tasker's repository selector replace the execution repository and resolve that repository's own credential reference without rewriting the Saved Job revision.
- Shared progress now carries `run_id` and optional `job_id` in addition to phase, message, progress, file counts, and byte counts. Android maps the same bridge document rather than independently recomputing progress identity.
- Tasker V2 now publishes `%bkx_success` and `%bkx_message` compatibility aliases as well as the prior status/error outputs. Saved Restic jobs publish snapshot ID, processed files/bytes, added/reused bytes, and duration from the actual shared execution result.
- CLI parity adds `bkx repository status <id>` and positional snapshot context (`bkx snapshot show phone-backup latest`, `bkx snapshot diff phone-backup latest~1 latest`, etc.) while retaining the existing explicit `--repository` / `--repository-ref` forms.
- `bkx job run --dry-run` is routed through the same `jobs.plan` pipeline rather than starting a real execution with a dry-run-looking flag.
- The normal Android Saved Job editor is now product-oriented: Backup type, Sources, Repository/Destination, Exclusions, Verify, Retention context, Notifications context, Tags, and Enabled are presented first. Raw operation IDs, credential-reference IDs, and JSON are kept under Advanced for custom/legacy jobs.

`bkx_br20_android_final_backup_automation_seal_v6.zip` supersedes both the failed v4 and the interim v5 artifacts. It targets the same post-BR19 Go baseline and is intended to be the single final, fully validating BR20 overlay.


## v7 validation/startup hotfix

The v7 replacement keeps the complete BR01-BR20 v6 implementation intact and fixes two final-gate integration regressions found by the full validation run. Android startup now publishes the private run-history cache immediately instead of launching canonical `runs.list` synchronization from `BkxAppViewModel.init`; canonical Go history remains authoritative whenever History is opened, runs are explicitly refreshed, or a recorded operation completes. This removes process-scoped I/O from ordinary ViewModel startup/test setup without weakening cross-client history convergence.

The legacy runtime-hardening Gradle gate also now matches the BR20 concurrency contract. It rejects a process-global `foregroundOperationMutex`, requires `executeOwnedConcurrent`, and requires idle-aware foreground-service shutdown through `stopForegroundShellIfIdle` / `anotherForegroundOperationIsActive`. This is consistent with the final roadmap seal and preserves parallel execution for unrelated repositories while repository/job leases remain the authoritative concurrency guard.

`bkx_br20_full_backup_jobs_tasker_release_seal_v7.zip` supersedes v6 on the same post-BR19 baseline and remains a full-validation final overlay.
