# Android product roadmap seal

This document is the final acceptance matrix for the Android product work that followed the Go migration. It is evidence, not a substitute for tests: `scripts/verify-ov56-roadmap-seal.sh`, Gradle product contracts, Go tests, lint, and the Android release seal enforce the live tree.

| Phase | Promise | Release evidence |
| --- | --- | --- |
| OV1 | Native Zstd create/read/verify/restore; split archives; capability-gated Android options; custom archive name | `internal/archiveio`, multipart tests, bridge capability tests, backup planning contracts |
| OV2 | Immediate operation feedback, scoped result lifecycle, foreground/live progress, Android 16 progress surface, cancellation, terminal notifications | operation lifecycle contracts, foreground-service contract, progress/cancellation bridge tests |
| OV3 | Location-first backup/restore/transfer UX, real preflight, adaptive archive detail workflow, simplified More, warning cleanup | workflow redesign contracts, storage/preflight tests, adaptive UI release contracts |
| OV4 | Structured rclone runtime/config model, credential-free remote URIs, remote browser/transfer, destructive sync approval, runtime identity validation | `internal/rcloneops`, rclone runtime contract, remote transport checklist/verifier |
| OV5 | First-class Google Drive authorization plus verified cloud backup, archive browse/verify, and restore | Google Drive contracts, `internal/remotearchive`, remote backup/dispatcher tests, Drive checklist/verifier |
| OV6 | Cumulative product/release seal and roadmap promise replay | this matrix, `verifyBkxCloudProductReleaseSeal`, `verify-ov56-roadmap-seal.sh`, canonical `scripts/validate.sh` |

## Release invariants

The final product keeps protocol-v1 operation names frozen. Remote/cloud support is implemented through existing backup/archive/restore/filesystem/transfer services and additive readiness capabilities rather than a second Android-only engine.

Local and root execution remain Go-native. Google Drive does not change root policy. Rclone and Google authorization are optional boundaries: local backup/archive/restore continue to work without either.

The release is warning-clean for actionable source/lint warnings introduced by this roadmap. ADB-unavailable output on a build host with no connected device is environmental evidence and is intentionally not suppressed.
