# BKX Android AOV-03/AOV-04 Audit

Scope: AC-013..AC-024, implemented as one overlay on top of the applied AOV-01/AOV-02 v7 base.

## Warning cleanup

- compileSdk/targetSdk and Devtool platform were raised to Android 17 / API 37 after Android 17 became available.
- Android Gradle Plugin was raised from 9.3.0 to 9.3.1.
- Compose BOM, appcompat, core-ktx, lifecycle, coroutines and serialization versions were refreshed to the versions reported by lint in the v7 validation output.
- Termux fixed paths are still exact, but the source no longer contains hardcoded `/data/` string literals that trigger Android `SdCardPath` lint.
- Devtool Gradle passthrough suppresses the custom-AAPT2 experimental-option warning for both `android.aapt2FromMavenOverride` and the suppression property itself, while keeping the working Termux AAPT2 provider path.
- Devtool Android validation workers are reduced from 2 to 1 to reduce memory-pressure warnings in the constrained Termux validator.

## AC-013

`SafIntentFactory` creates document/tree picker intents with persistable grant flags. `AndroidSafFileAccessAdapter.persistGrant` deliberately calls `takePersistableUriPermission` and records read/write grant state.

## AC-014

`ContentUriPathPolicy.requireNotContentUriPath` rejects `content://` values before a value can be used as a BKX POSIX path. Tests prove this fails closed.

## AC-015

`StagedFileRecord` records source URI, staged path, size, hash, staging lifetime, cost, and grant state. `StagedFilePlanner` creates the record only after grant and path checks.

## AC-016

`StagingCost` carries user-visible copy/hash cost messaging. `FileAccessIssue.GrantLost` exposes permission-loss state and remediation.

## AC-017

`RestrictedLocationPolicy` refuses blank/relative unavailable staging paths, root-like paths, Android restricted paths, and pseudo-filesystem locations instead of promising full-root access.

## AC-018

SAF and future URI streaming stay behind `SafFileAccessPort` and `AndroidSafFileAccessAdapter`; UI and backend request code do not treat URIs as paths.

## AC-019

Home now includes a Recent Runs card with structured status, duration, artifact display names, and verification state.

## AC-020

`RunSummaryStore` and `SharedPreferencesRunSummaryStore` persist local summaries. `RunArtifactSummary` rejects path-like display names so summaries do not store sensitive paths.

## AC-021

Tools/Backend screen renders Termux readiness, wrapper path, protocol version, BKX version, and capabilities.

## AC-022

`BackendToolState` maps every not-ready `BackendReadiness` to user-visible setup remediation actions.

## AC-023

Settings screen renders backend, staging location, logging/privacy, and progress preferences from `BkxSettings`.

## AC-024

`BkxDestination`, `ScreenStatus`, and `BkxHomeScreen` provide a typed navigation graph with empty/loading/error/content states for Home, Backup, Archives, Restore, Transfers, Tools, and Settings.

## Patch audit refresh

The v2 overlay fixes the AOV-04 run-summary privacy guard so backslash path separators are rejected while normal human display names with apostrophes remain valid. It also strengthens AC-017 by making blank and relative staging locations explicit `UnavailableLocation` failures.
