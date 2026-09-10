# BKX automation, Tasker, and explicit-component Intent API

BKX exposes one Android automation boundary over the same operation coordinator and protocol-v1 Go engine used by the product UI. Automation does **not** bypass backup planning, transfer preview/collision checks, restore preview/confirmation, Google Drive authorization, cancellation, run history, or the root routing policy.

## Tasker action plug-in

BKX implements the Locale/Tasker setting plug-in protocol directly. No Tasker SDK/library is bundled.

1. Open **BKX → More → Automation** and leave External automation enabled. Saving a BKX Tasker action also enables it automatically.
2. In Tasker create a Task, add **Plugin → BKX Automation**, then tap Configure.
3. Choose the operation and fill its fields. String fields accept Tasker variable replacement.
4. Leave **Request ID** blank for normal use. BKX creates a fresh request ID each time the Tasker action fires. Set an explicit request ID only when you deliberately want idempotent retry semantics.
5. Leave **Wait for final result** enabled when a later Tasker action needs the `%bkx_*` result variables.

BKX accepts Tasker/Locale edit/fire traffic only through its dedicated plug-in surface. Tasker configurations receive a private Tasker-only capability token; the direct Intent API uses a separate token. Regenerating the token in BKX invalidates both and existing Tasker actions must be edited/saved again.

### Tasker actions

| Action | Required input | Important options |
| --- | --- | --- |
| Backup | absolute local `source`; local or `rclone://` `destination` | archive name, include/exclude, hidden files, gzip/zstd/none, split size, verification |
| Restore | local or `rclone://` `archive`; absolute local `destination` | subset, fresh/existing mode, verify, exact destructive confirmation |
| Verify | local or `rclone://` archive | — |
| Archive list | zero or more local/`rclone://` roots | max depth, limit |
| Archive info | local or `rclone://` archive | — |
| Archive browse | local or `rclone://` archive | page token, page size |
| Transfer | one or more local/`rclone://` sources; local/`rclone://` destination | push/pull/sync, checksum, overwrite, verification, explicit sync deletion approval |
| Cancel | target automation/request ID | — |
| Status | target automation/request ID | — |

`rclone://` values are credential-free references only. BKX never accepts an rclone password, OAuth token, Google access token, config blob, or arbitrary shell command through automation extras.

### Returned Tasker variables

When asynchronous completion is supported and **Wait for final result** is enabled, BKX returns:

- `%bkx_request_id`
- `%bkx_action`
- `%bkx_status`
- `%bkx_operation_request_id`
- `%bkx_summary`
- `%bkx_error_code`
- `%bkx_error_message`
- `%bkx_artifact_paths` (newline-separated)
- `%bkx_artifact_sha256` (newline-separated)
- `%bkx_files_total`
- `%bkx_bytes_total`

A Tasker host without asynchronous completion support receives the accepted/queued result immediately; the BKX foreground notification remains the execution indicator.

## Safety and idempotency

Automation request IDs are logical idempotency keys. While a request is in this process, duplicate submissions join the same execution. Terminal results are persisted privately and a later submission with the same ID returns the existing result rather than creating a second backup/restore/transfer. If Android kills the process after a stage was persisted but before a terminal receipt was committed, reusing that ID fails closed with `automation_previous_dispatch_incomplete`; query Status and retry with a new ID only after deciding that a repeat is safe.

Backup always runs `backup.plan` before `backup.create`. Transfer always runs `transfer.plan` before `transfer.run`. Restore always runs `restore.preview` before `restore.extract` and uses the existing BKX safety model. When restore preview reports overwrites, the Tasker action must supply exactly `RESTORE`; when the existing root-sensitive destination policy applies, it must supply exactly `RESTORE ROOT`.

Cancel targets the currently persisted internal stage when one exists, so a caller can cancel by the outer automation request ID instead of knowing the derived bridge request ID.

## Google Drive

The managed Google Drive root is `rclone://bkx-google-drive/BKX`. Before an automation operation touches that managed root, Android silently asks `GoogleDriveAuthorizationManager` to refresh/project the current authorization into BKX's private rclone config. Automation never launches an interactive consent UI in the background. If consent is required or refresh fails, the result is `google_drive_authorization_required`; open **BKX → More → Google Drive**, reconnect, then run the automation again.

Imported generic rclone remotes remain independent and use the same credential-free `rclone://REMOTE/path` interface.

## Direct explicit-Intent API

For Tasker, prefer the plug-in UI. The explicit API exists for ADB, shell automation, and other Android automation apps.

Enable it under **BKX → More → Automation** and copy the direct capability token. Every direct request must be an explicit broadcast to `com.mikeyphw.bkx.android/.automation.BkxAutomationReceiver` and include that token.

Example backup:

```bash
adb shell am broadcast \
  -n com.mikeyphw.bkx.android/.automation.BkxAutomationReceiver \
  -a com.mikeyphw.bkx.android.automation.BACKUP \
  --es com.mikeyphw.bkx.android.automation.extra.ACCESS_TOKEN 'TOKEN_FROM_BKX' \
  --es com.mikeyphw.bkx.android.automation.extra.REQUEST_ID 'phone-nightly-2026-09-09' \
  --es com.mikeyphw.bkx.android.automation.extra.SOURCE '/storage/emulated/0/DCIM' \
  --es com.mikeyphw.bkx.android.automation.extra.DESTINATION 'rclone://bkx-google-drive/BKX' \
  --es com.mikeyphw.bkx.android.automation.extra.COMPRESSION 'zstd'
```

If `REQUEST_ID` is omitted, BKX generates one and returns it in the ordered-broadcast result extras.

For a final asynchronous callback, provide both:

- `com.mikeyphw.bkx.android.automation.extra.CALLBACK_PACKAGE`
- `com.mikeyphw.bkx.android.automation.extra.CALLBACK_ACTION`

BKX then sends an explicit package-scoped broadcast containing `RESULT_JSON`, request ID, status, error code/message, and summary. The callback is only accepted on the inbound request after the caller has demonstrated the capability token.

Status example:

```bash
adb shell am broadcast \
  -n com.mikeyphw.bkx.android/.automation.BkxAutomationReceiver \
  -a com.mikeyphw.bkx.android.automation.STATUS \
  --es com.mikeyphw.bkx.android.automation.extra.ACCESS_TOKEN 'TOKEN_FROM_BKX' \
  --es com.mikeyphw.bkx.android.automation.extra.TARGET_REQUEST_ID 'phone-nightly-2026-09-09'
```

Cancel uses the same shape with action `com.mikeyphw.bkx.android.automation.CANCEL`.

## Android background execution

The exported receiver validates and persists the request before dispatch. Long work runs in BKX's internal data-sync foreground service; it does not run inside `BroadcastReceiver.onReceive`. If Android rejects the immediate foreground-service start, BKX schedules its system-protected JobService fallback. Dispatch records live only in private app storage, are replayed by the service after restart, and are deleted after result delivery.

The automation service owns the notification while external automation is running. The shared `BkxOperationCoordinator` retains its normal foreground behavior for UI-initiated operations; automation calls it with foreground management disabled so two services do not compete for the same work.

## Credential/token handling

- The direct capability token and Tasker-only token are random 256-bit values stored only in BKX private SharedPreferences.
- The Tasker configuration Activity only returns a Tasker token to trusted Tasker/Locale hosts.
- The direct token is shown/copyable only from BKX's Automation screen.
- Google OAuth and imported-rclone credentials never cross the automation Intent boundary.
- Regenerate tokens after copying a Tasker configuration to an untrusted device or if the direct token may have leaked.

## Tasker editor UX

The Tasker configuration Activity is intentionally optimized for small screens and repeated automation setup:

- one compact operation picker replaces the nine-chip action wall;
- the selected operation includes a short explanation and the exact Tasker blurb preview;
- required inputs appear first while uncommon controls live under **Advanced options**;
- compression, restore mode, and transfer mode use guided fixed choices, with an explicit **Use Tasker variable** escape hatch so dynamic Tasker workflows remain possible;
- required/path/numeric mistakes are reported inline before **Save to Tasker** becomes available;
- restore and sync deletion requirements are visually separated as safety guidance rather than mixed into ordinary fields;
- returned `%bkx_*` variables are collapsed by default and can be expanded when building downstream Tasker steps;
- the bottom Cancel/Save bar stays reachable while scrolling and respects gesture-navigation and IME insets.

The editor performs only configuration-time guidance. The authoritative `AutomationRequest.validate()` still runs after Tasker variable replacement when the action fires, so editor convenience never weakens runtime validation or the existing backup/restore/transfer safety gates.
