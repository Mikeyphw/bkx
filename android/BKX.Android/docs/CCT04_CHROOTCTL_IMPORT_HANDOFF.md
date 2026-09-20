# CCT-04 — chroot import handoff and guarded native import

BKX never extracts a chroot archive itself. The app first acquires an archive into its own external-files `bkx/chrootctl-import` staging area using the existing local/Google Drive/rclone transfer paths. Only a canonical file beneath that directory may cross the privileged handoff.

The UID-0 service copies the descriptor into chrootctl's private `/data/local/tmp/chrootctl-transfer` staging directory, publishes atomically with mode 0600, and writes a SHA-256 sidecar. No arbitrary privileged read/write path API is exposed.

BKX then calls `transfer.import_preview`. Archive inspection, expanded-size/storage strategy, suggested identity, checksum status and metadata fidelity come from chrootctl. `legacy_unverified` is blocked unless the user explicitly enables legacy compatibility. The execution payload is bound to the preview proof and uses `postImport=register`.

Import requires an explicit interactive `confirmationGranted` gate. Scheduled/unattended import is not exposed by this coordinator. Cancellation sends `chrootctl app cancel <operationId>` when an operation id has been observed; interrupted streams reconcile against chrootctl's persistent operation journal instead of replaying the mutation.

Conflict choices are limited to `rename` or `overwrite`; strategy is `auto`, `staged`, or `streaming`. CCT-07 owns the user-facing import screens and confirmation copy.

CCT-04 v2 closes staging lifecycle gaps. Local acquisition checks app staging free space before copying. After a verified descriptor handoff, the duplicate BKX acquisition file is removed. Preview failure cleans the exact root staging object. Terminal imports use a narrow staging-id + checksum cleanup call that revalidates the root-owned directory, canonical artifact and SHA-256 before deletion. Cancellation intentionally retains root staging until chrootctl reaches a known terminal state; `cleanupRetained` provides explicit recovery cleanup. A successful import reports `retainedStagingId` when cleanup could not be proven, rather than silently claiming complete cleanup.
