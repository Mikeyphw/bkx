# chrootctl 1.3.3 / module 18 compatibility hotfix

BKX continues to consume chrootctl bridge protocol v1 through its fixed AIDL/root-service boundary. This hotfix mirrors the current transfer import compatibility aliases (`allow_legacy_metadata`, `import_strategy`, `post_import`) in the root-process duplicate allowlist while preserving the existing camelCase vocabulary emitted by BKX.

The minimum supported module versionCode remains 15: current module 1.3.3 / versionCode 18 is added as a compatibility fixture, not a new minimum. No generic shell, HTTP WebUI transport, or additional privileged operation is exposed.
