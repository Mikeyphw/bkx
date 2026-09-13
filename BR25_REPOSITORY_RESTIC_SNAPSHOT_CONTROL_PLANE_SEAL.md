# BR25 — Repository, Restic and Snapshot Control Plane Seal

This intermediate remediation overlay implements the BR25 roadmap unit for repository catalog authority, Restic credential resolution, snapshot list/diff/browse semantics, and Android request/codec bounds.

## Sealed contracts

- Repository credentials now use a collision-free product grammar and one canonical `BKX_CREDENTIAL_*` / `BKX_CREDENTIAL_FILE_*` mapping.
- Restic credential resolution rejects competing password channels and scrubs direct `RESTIC_PASSWORD*` when a named credential reference is used.
- Repository store validation rejects multiple named repositories pointing at the same physical backend/location.
- Repository removal detects saved-job and schedule dependents and refuses removal unless the request explicitly confirms dangling-dependent handling.
- Snapshot lists sort by parsed RFC3339 timestamps instead of lexical strings and are bounded/truncation-aware.
- Snapshot browse/diff parsing fails closed on malformed Restic JSON records instead of silently reporting partial success.
- Snapshot diff output is bounded, carries truncation metadata, and Android codecs surface that truth to the UI layer.

## Deferred gate

Use `:app:verifyBkxRepositoryResticSnapshotControlPlaneSeal` in the later validation gate together with unit tests and lint.
