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
