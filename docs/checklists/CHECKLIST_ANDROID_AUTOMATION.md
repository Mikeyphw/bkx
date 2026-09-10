# Android automation / Tasker release checklist

- [ ] `verifyBkxAutomationProductContract` passes.
- [ ] `AutomationProductContractTest` and `BkxAutomationEngineTest` pass in `:app:testDebugUnitTest`.
- [ ] Tasker discovers exactly one BKX **Automation** setting plug-in and opens the configuration Activity.
- [ ] Blank Request ID produces a fresh request ID per fire; a fixed Request ID returns the same terminal receipt instead of repeating work.
- [ ] Backup runs plan → create; transfer runs plan → run; restore runs preview → extract.
- [ ] A restore with overwrite/root risk is blocked until the exact `RESTORE` / `RESTORE ROOT` phrase is supplied.
- [ ] `%bkx_*` final variables are populated when Tasker asynchronous completion is available.
- [ ] Status and Cancel work with the outer automation request ID.
- [ ] Managed Google Drive refresh succeeds without exposing OAuth tokens; required re-consent returns `google_drive_authorization_required` instead of opening UI in background.
- [ ] Regenerating Automation tokens invalidates an existing Tasker action and the old direct Intent token.
- [ ] A non-Tasker app cannot obtain the Tasker-only configuration capability through the exported edit Activity.
- [ ] Direct Intent without the copied capability is rejected; with it, explicit package/component dispatch succeeds.
- [ ] Callback package/action pair receives package-scoped final result JSON and no cloud credentials.
- [ ] External automation runs under the BKX automation foreground service; UI-initiated operations retain the normal coordinator foreground path.
- [ ] Force-stop/process-death recovery does not blindly replay a persisted active request ID.
- [ ] `lintDebug`, `assembleDebug`, `assembleRelease`, and `bkxAndroidReleaseSeal` pass.

## Tasker Event / State condition seal

- [x] BKX Event and BKX State appear as distinct Tasker plugin contexts.
- [x] Event updates originate from the process-scoped BKX operation coordinator, including work launched from the normal BKX UI.
- [x] State queries read persisted operation state plus minimal redacted Tasker signal state.
- [x] Start, canonical progress milestone, terminal success/failure/cancel, operation-specific completion, archive-created, Drive-auth-required, remote-unavailable, and remote-recovered events are represented.
- [x] Running, Drive authorization, remote availability, and filtered last-terminal states are represented.
- [x] Progress is deduplicated by request id and canonical 5% bucket.
- [x] Operation/request/error/remote/progress filters are deterministic and wildcard-aware.
- [x] Terminal history stores one canonical terminal signal per request id.
- [x] Google Drive connected is observation-based, not inferred from config presence.
- [x] OAuth/rclone/capability secrets and complete local paths are excluded from persisted/emitted signals.
- [x] Tasker condition library is pinned; its competing Action components are removed from manifest merge.
