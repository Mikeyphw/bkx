# AOV-13 through AOV-16 Audit — Embedded BKX Engine Cutover

## Scope

This mega overlay changes the Android execution architecture, not the product UI.
`bkx.core` remains canonical and is embedded in the APK through Chaquopy.

## AOV-13 — Embedded Python build lane

Implemented:

- Chaquopy `17.0.0` plugin added to the version catalog and Android app module;
- AGP pinned to `9.2.1`, within Chaquopy 17's tested 9.2.x lane;
- `minSdk` raised from 23 to 24;
- compile/target SDK remains 36;
- Java remains 21 and the final Devtool Gradle contract requires 9.7.1 or newer;
- Python originally used 3.13; the final release seal now selects 3.13 or 3.14 to match the available build interpreter, as required by Chaquopy 17;
- `arm64-v8a` and `x86_64` packaged;
- Chaquopy reads the repository `src` tree directly, preserving one canonical
  implementation of `bkx.core`;
- `BkxAndroidApplication` starts Python exactly once per process;
- `verifyBkxEmbeddedEngineContract` fails closed on build-lane/source-boundary
  drift.

No Python CLI/TUI dependency is installed into the APK by the Chaquopy `pip`
configuration. The Android API imports `bkx.core` directly.

## AOV-14 — `bkx.android_api`

Implemented a JSON-only Android boundary under `src/bkx/android_api`.

Supported contract operations:

- `backup.plan`
- `backup.create`
- `archive.list`
- `archive.info`
- `archive.browse`
- `archive.verify`
- `restore.preview`
- `restore.extract`
- `transfer.plan`
- `transfer.run`
- `runs.list`
- `runs.show`
- queued-request cancellation

Properties:

- protocol version 1 remains compatible with existing Android bridge DTOs;
- malformed/unsupported requests fail closed as structured results;
- typed `BkxError` failures are translated to structured Android errors;
- plan tokens are SHA-256 digests of the canonical core execution plan;
- backup/archive/restore/local-transfer operations call services from
  `bkx.core.services.factory` directly;
- no `argparse`, CLI app, Textual, arbitrary expression execution, or shell
  dispatch is present in the embedded API.

`archive.list` is temporarily derived from BKX run history because the old Android
request does not provide a storage root. The AOV-17→20 SAF archive index replaces
that temporary projection.

## AOV-15 — `EmbeddedBkxBackend`

Implemented:

- `EmbeddedPythonGateway` JSON boundary;
- `ChaquopyPythonGateway` as the only Chaquopy-aware production adapter;
- `EmbeddedBkxBackend` implementing the existing `BackendPort`;
- strict readiness parsing for runtime `embedded-cpython`;
- strict final-result parsing before publication;
- request/result atomic-ish publication retained as a migration seam;
- production `DefaultBackendGraph` now selects `EmbeddedBkxBackend`;
- legacy `TermuxBridgeAdapter` remains in source but is not instantiated by the
  production graph.

The existing `BackendReadiness.Ready` type now records runtime identity and
renders an embedded-engine readiness message when appropriate.

## AOV-16 — Parity/regression harness

Added Python parity tests which execute real operations through the Android JSON
boundary and compare plan tokens with direct canonical-core planning.

Covered:

- readiness/capability contract;
- backup plan parity;
- real backup creation;
- archive verify and browse;
- restore preview and extraction;
- local transfer plan parity and execution;
- cancellation before queued execution;
- malformed and unsupported request rejection.

Added Kotlin backend tests for:

- embedded readiness mapping;
- strict request/result publication;
- malformed Python result rejection before publication;
- embedded cancellation dispatch without shell execution.

Added repository-level static regression tests locking the Chaquopy build lane,
canonical source tree, production graph cutover, retained legacy parity adapter,
and embedded verification gate.

## Compression note

The Android default backup compression is changed from Zstd to Gzip in this
embedded lane. The current canonical fallback archiver executes Gzip entirely
in-process with the Python standard library. Explicit Zstd remains a real BKX
request and is not silently downgraded; native/in-process Zstd capability can be
added deliberately in AOV-17→20.

## Deferred work

This overlay intentionally does **not** claim completion of:

- final SAF staging/index architecture;
- active-operation cooperative cancellation;
- removal of legacy Termux manifest/source contracts;
- product UI redesign;
- final device/release qualification.

Those remain assigned to AOV-17→20, AOV-21→24, and AOV-25→32 respectively.
