# AOV-21→24 completeness audit — Material 3 product UI

## AOV-21 — design system and product shell

Implemented:

- `BkxTheme` now provides explicit light/dark BKX schemes plus Android 12+ dynamic color.
- `AppearancePreference` and `dynamicColor` persist in `BkxSettings`.
- typography and spacing tokens live under `app/ui/theme`.
- reusable product components live under `app/ui/components`.
- edge-to-edge is enabled in `MainActivity`.
- primary destinations are exactly Home, Backup, Archives, Activity, More.
- phone UI uses `NavigationBar`; wide/tablet UI uses `NavigationRail`.
- Restore, Transfer, Tools, and Settings are no longer developer-style top-level tabs.

## AOV-22 — Home and Backup

Implemented:

- Home shows root/app privilege state in plain language.
- All files access is surfaced as a user action when missing.
- quick actions open Backup and Archives.
- active operation and recent activity are summarized without request IDs or paths.
- Backup edits direct source/destination filesystem paths and exposes shared/Termux presets.
- compression, verification, hidden-file, symlink, and split controls are user-facing.
- Review invokes `backup.plan` through `BackendPort`.
- Create invokes `backup.create` only after the matching executable review.
- plan tokens and bridge/file protocol internals are not rendered.

## AOV-23 — Archives and contextual Restore

Implemented:

- archive list refresh is wired to embedded `archive.list`.
- archive list search and newest/name/size sorting are available without exposing engine metadata.
- arbitrary absolute archive paths can be opened directly.
- info, browse, pagination, and verification call canonical embedded operations.
- loaded archive pages support text filtering and file/folder/link/special filtering, with directories grouped first.
- selected archive opens a contextual Restore panel.
- restore destination mode, verification, review, and extraction are wired.
- destructive overwrite requires the existing `RESTORE` typed confirmation contract.
- unsafe paths/links/special-file blocks remain visible as safety errors, without weakening core decisions.

## AOV-24 — Activity, More, transfers, settings and diagnostics

Implemented:

- Activity separates live operations from compact persisted history.
- persisted history stores display names rather than full artifact paths.
- notification/reconnect focus now opens Activity.
- Local transfer lives under More with source/destination, mode, verification, overwrite, sync-delete, review and run actions.
- root policy, appearance, dynamic color, log privacy, and progress preferences persist through `SettingsStore`.
- All files access and root-readable quick locations are surfaced under More.
- engine version/protocol/capabilities/remediation details are hidden behind Advanced diagnostics.

## Regression gates

Added:

- Gradle `:app:verifyBkxProductUiContract`.
- Python `tests/test_android_material3_product_ui_contract.py`.
- navigation and ViewModel tests updated for the five-destination product shell.
- settings persistence and notification-to-Activity behavior are covered.

## Deliberate deferrals to AOV-25→32

- physical-device visual qualification and screenshots.
- instrumentation accessibility assertions.
- cooperative cancellation during an already-running Python filesystem operation (the embedded API currently guarantees queued/pre-start cancellation only).
- large-tree performance profiling and memory-pressure tuning.
- final warning/lint/release seal.
