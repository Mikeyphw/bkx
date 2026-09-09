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
