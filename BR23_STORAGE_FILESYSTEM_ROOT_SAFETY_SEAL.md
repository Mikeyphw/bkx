# BR23 — Storage, Filesystem and Root Safety Seal

This overlay implements the third deduped BKX remediation unit from the A01-C17 roadmap. It targets U07, U08 and U09: root/local execution ownership, canonical filesystem policy, protected Android destinations, and provider staging/capacity truth.

## Product contracts sealed

- Android root execution is request/path-aware. Preferred root mode no longer escalates ordinary shared-storage operations, and it fails closed instead of silently falling back to local execution for root-sensitive paths when root is unavailable.
- Root/local routing pins filesystem, backup, restore, repository and transfer requests to the route implied by their effective paths.
- Android path policy canonicalizes slash, traversal and storage-alias forms before checking protected locations.
- `/Android/data`, `/Android/obb`, pseudo-filesystems, system partitions, root-level destinations and private app/Termux trees are classified consistently.
- Provider staging publishes a lease id, lease expiry and active-source metadata; pruning skips active leases.
- Provider staging performs a capacity preflight before copying provider streams and reserves headroom so cache pressure is surfaced before partial staging starts.
- Go validation enforces the same canonical local path policy for backup, restore, transfer, filesystem and archive materialization paths.
- Local storage discovery reports usability metadata instead of treating existence as enough to advertise direct actions.

## Covered roadmap units

- U07 — Root execution, ownership and privileged route safety.
- U08 — Filesystem path canonicalization and protected-destination policy.
- U09 — Storage/provider staging, capacity and access truth.

## Intended apply mode

Intermediate overlay: apply with `--no-validate`. A later gate overlay should run `:app:verifyBkxStorageFilesystemRootSafetySeal`, unit tests, lint, and Go tests.
