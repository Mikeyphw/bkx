# BKX Android AOV-13→32 completeness replay

## Method

The four mega-overlays were replayed in order from the original `bkx-20260820-232652` snapshot with source SHA-256 verification before every modify/delete action and payload SHA-256 verification for every added/modified file:

1. AOV-13→16 — embedded engine cutover;
2. AOV-17→20 — root/native storage runtime;
3. AOV-21→24 — Material 3 product UI;
4. AOV-25→32 — final hardening/release seal.

All four original artifacts replayed cleanly. The resulting tree was then audited against the roadmap promises rather than against the milestone audit prose. Functional gaps found in that finished v1 tree are repaired by the AOV-25→32 v2 final overlay.

## Promise-by-promise result

| Roadmap promise | Final status | Evidence / correction |
|---|---|---|
| Chaquopy embedded CPython | Delivered | Chaquopy 17.0.0 with build-host-matched Python 3.13/3.14, `minSdk 24`, canonical repository `src` source set. Final seal v5 explicitly sets `buildPython` so Termux upgrades do not break source merging. |
| Supported Android toolchain lane | Delivered | AGP 9.2.1, compile/target SDK 36, Java 21, Gradle/Devtool contract retained. |
| Canonical `bkx.core`, no duplicated Android engine | Delivered | `bkx.android_api` calls `bkx.core.services.factory`; Android packages the repository source tree directly. |
| Structured embedded Android API | Delivered | backup plan/create; archive list/info/browse/verify; restore preview/extract; local transfer plan/run; run history; filesystem stat/list; progress/cancel. |
| Production embedded backend | Delivered | `DefaultBackendGraph -> EmbeddedBkxBackend -> RootAwarePythonGateway`. |
| Root access patterned on MMRL/libsu | Delivered | libsu 6.0.0 `RootService`; UID-0 assertion; root/local routing; no arbitrary privileged shell endpoint. |
| Root access to Termux private storage | Delivered | first-class `/data/data/com.termux/files`, `home`, `home/storage`, and `usr` paths plus root filesystem browsing. |
| Root preferred / required / disabled | Delivered | root policy evaluated for every new operation; active request route pinned for progress/cancel. |
| All Files access | Delivered | `MANAGE_EXTERNAL_STORAGE` onboarding/state with direct shared-storage paths. |
| Direct path browser | **Repaired in v2** | reusable root-aware Compose filesystem browser executes canonical `filesystem.list`. |
| Storage volume discovery / SD / USB | **Repaired in v2** | `StorageManager.storageVolumes` plus external-files compatibility discovery; product quick locations and archive roots use it. |
| SAF fallback only for provider-only inputs | **Repaired in v2** | document/tree provider streams are copied into bounded app-private staging; ordinary paths bypass staging. |
| Bounded provider staging | **Repaired in v2** | 1 MiB streaming buffer, path containment, safe names, depth 64 and total-entry 100,000 limits, stale staging pruning. |
| Native process-scoped operation runtime | Delivered and hardened | application-scoped coordinator owns work; FGS protects long operations; process-death leftovers become Interrupted. |
| Live progress | **Repaired in v2** | coordinator publishes authoritative `StateFlow`; UI observes it continuously; FGS notifications refresh from the same state. |
| Cooperative cancellation | Delivered and **product-wired in v2** | root/local Python cancellation checkpoints plus Activity `Cancel operation`. |
| Notification denial does not abandon work | **Repaired in v2** | data-sync FGS remains active even when notification permission is unavailable. |
| Backup workflow | Delivered | direct/root/provider source selection; destination selection; canonical review/token; create; gzip default; verification; symlink-skip truth. |
| Archive index | **Repaired in v2** | bounded filesystem scan of discovered `<volume>/BKX` roots merged with canonical run history; real size/mtime metadata. |
| Archive search/sort | Delivered and made truthful | search plus name/newest/size sort now operate on real metadata. |
| Archive browse file/folder/link/special filters | **Repaired in v2** | canonical archive entries classify tar/ZIP file, directory, symlink and special entries; Android gets distinct artifact labels. |
| Restore workflow and safety | Delivered | contextual restore; fresh/existing semantics; traversal/link/special-file safety; protected roots; UID-0 + exact `RESTORE ROOT` for root-sensitive destinations. |
| Local transfer | Delivered | plan/run, copy/sync, verify, overwrite/delete controls, direct/root/provider source paths. |
| Termux execution architecture removed | Delivered | no RUN_COMMAND permission, Termux bridge adapter, invocation factory, or serialized `termux_run_command` production token. |
| Five-destination Material 3 product UI | Delivered | Home, Backup, Archives, Activity, More; bottom bar phone and navigation rail wide/tablet. |
| Appearance/dynamic color/root/privacy/progress settings | Delivered; runtime effects repaired | privacy controls Activity/notification detail; progress preference controls polling cadence. |
| Accessibility qualification | **Repaired in v2** | Compose instrumentation asserts the five navigation actions exist, display and click. |
| Physical-device visual qualification | **Repaired in v2** | opt-in qualifier captures and retrieves five product screenshots. |
| Large-tree performance/memory profile | **Repaired in v2** | deterministic 768-file nested-tree backup/verify/browse profile with symlink escape trap and peak-memory/wall-time limits. |
| Embedded/core parity seal | Delivered | stdlib-only parity executable covers plan/create/verify/restore/transfer/progress metrics. |
| Source/dependency hygiene | Delivered | no production Termux execution/generic root shell; no committed keystores/APKs/pyc/source caches; pinned stable lane. |
| Rooted-device qualification | Delivered and extended | explicit all-files + UID-0 + Termux private stat + non-root fallback; v2 adds accessibility/screenshots. |
| Release docs/signing/version | Delivered | 1.0.0 / code 10000, signing placeholders, final docs, manifest contract and release seal. |
| Devtool final validation matrix | **Strengthened in v2** | storage completeness, large-tree performance and accessibility/visual gates are mandatory preflight tasks in addition to the original final matrix. |

## Validation performed while preparing v2

The generation environment completed:

- strict replay of all four original overlays;
- focused embedded/root/product/final-contract tests;
- broad runnable non-TUI repository test suite (optional `hypothesis`, `textual`, and `mikomn` suites are unavailable in this environment);
- embedded/core parity executable;
- 768-file large-tree performance/memory profile;
- Python AST and JSON-schema parsing;
- `.devtool.toml` / version-catalog TOML parsing;
- Android XML parsing;
- shell syntax checks;
- production-source scan for Termux execution and generic root-shell tokens.

Android compilation, lint, APK assembly and connected-device qualification remain intentionally owned by the final Devtool validation on the target Termux/Android environment.

## Final conclusion

After the v2 corrections above, every promise in the AOV-13→32 roadmap has a concrete production implementation or an explicit final-device validation gate. Termux is a root-accessible storage target only. `bkx.core` remains the single canonical engine, and Android owns lifecycle, permissions/root authorization, product UX, persistence, foreground protection and provider adaptation.


## Final validation follow-up — v5 build Python

A real Termux validation run reached Chaquopy source merging and proved that the
original Python 3.13 pin was not portable across current Termux package upgrades.
V5 keeps Chaquopy 17 and the 64-bit ABI lane, but explicitly resolves build Python
3.13 or 3.14 and packages the matching runtime. This preserves the canonical
embedded engine while avoiding PATH/version autodetection failures.
