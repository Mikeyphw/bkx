# BKX Android release contract

The release gate is feature-oriented rather than milestone-oriented. It must prove:

- stable API-36 / Java-21 / Gradle dependency lane;
- embedded-engine and protocol-v1 integrity;
- root/storage capability boundaries with no generic root shell transport;
- Material 3 product navigation and production terminology;
- process-scoped operation recovery, progress, cancellation, and bounded history;
- protected-destination and archive extraction safety;
- representative large-tree performance bounds;
- direct/root/removable/SAF storage completeness;
- accessibility and opt-in physical-device visual qualification;
- embedded/core parity;
- source/dependency/release hygiene;
- manifest and signing-placeholder checks;
- unit tests, lint, debug/release packaging, and connected instrumentation when a device is available.

The canonical application version is read from repository-root `internal/version/version.go`. Android derives its `versionCode` from that semantic version, so the Go CLI and Android package cannot silently drift.

## BR35 deployability seal

The BR35 release packaging, signing and native runtime seal makes release artifacts reproducible enough to audit: Gradle-staged release gates separate preflight, packaging and instrumentation; the generated Go runtime is stamped with a real Git commit; release lint and merged-manifest validation are explicit; the physical qualification script can install and verify the exact release APK; dependency locking is enabled for resolved Android artifacts; release builds are minified/resource-shrunk; and split archives publish a sidecar manifest so sidecar association is no longer inferred only from filename adjacency.

## REL-00–08 Embedded/Lite release qualification

The current release contract is `docs/release/DUAL_VARIANT_CONTRACT.md` and
`docs/release/REL08_FINAL_EVIDENCE_CLOSURE.md` at repository root. GitHub builds
**two unsigned** release APKs from one immutable workflow run. Devtool RM-16
verifies/imports both and locally signs each with the **existing** BKX LIFE
identity, keeping signing credentials off GitHub. Embedded has rclone; Lite
forbids rclone; both contain `libbkxgo.so` and preserve the authorized read-only
Skybind R1 client.

Neither source/Gradle gates nor REL-07 APK-swap evidence alone satisfy the
production release contract. Real signed-APK identities, independent Android
Go/root/instrumentation tests, two Skybind R1 gateway campaigns, negative
authorization behavior, backup/restore/Tasker behavior, and retained user data
are required before independent release authority can close the gates. The
REL-08 tool intentionally always reports `qualified_release=false`.
