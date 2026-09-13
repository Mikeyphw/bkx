# BR30 — UI Truthfulness, Accessibility and Product Copy Seal

**Overlay 10 of 16** in the BR21–BR36 BKX remediation roadmap.

## Scope

BR30 closes dedupe units U29 and U30:

- UI truthfulness, capability gating and product copy.
- Accessibility, localization and scalable shared layouts.

## Product contract

- The app no longer says READY/CONNECTED/SAFE/COMPLETE based on optimistic UI state alone.
- Home readiness copy comes from `BkxHomeUiState.uiTruthSurface()` and only reports a verified engine after `BackendReadiness.Ready`.
- Google Drive copy only reports verified cloud readiness when both Google authorization and the managed BKX rclone remote are ready.
- Backup review/start and transfer preview/run use `BkxHomeUiState.actionGate(...)`, so destructive or long-running operations are blocked while another BKX operation is active.
- Shared Compose components expose heading, status, empty-state and progress live-region semantics.
- Audited truthfulness/gating/accessibility copy has resource entries in `bkx_product_copy.xml` so future localization does not depend on embedded copy.

## Files changed

- `android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/BkxUiTruthPolicy.kt`
- `android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/ui/components/BkxComponents.kt`
- `android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/home/HomeScreen.kt`
- `android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/backup/BackupScreen.kt`
- `android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/transfer/TransferScreen.kt`
- `android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/more/GoogleDriveScreen.kt`
- `android/BKX.Android/app/src/main/res/values/bkx_product_copy.xml`
- `android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/contracts/UiTruthfulnessAccessibilityContractTest.kt`
- `build-logic/android-verification.gradle.kts`

## Future gate task

```bash
:app:verifyBkxUiTruthfulnessAccessibilityProductCopySeal
```

This is an intermediate remediation overlay. Apply with `--no-validate`; gate validation should run later when sensible.
