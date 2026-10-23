# BKX REL-06 — source-bound Devtool RM-16 release handoff

**State:** source implemented / real local authority pending. No hosted build, downloaded unsigned APK, local LIFE signing receipt, installed APK, or Skybind device evidence was produced while authoring this change. **`qualified_release` must remain `false`** until separate release/device gates are closed. This is the maintained promise-by-promise implementation record.

## Scope and sources

The reviewed BKX source inputs are `config/bkx-android-unsigned-artifacts.json` and `.github/workflows/bkx-android-unsigned-dual-release.yml` (REL-04/05). The trusted registration authority is Devtool `actions_android_contracts.load_consumers` (`config/android-consumers.json` under BKX repository root), while existing local LIFE authority is `actions_setup._signing_profile`, `actions_setup._keystore`, and `actions_unsigned.unsigned_local_sign`. **No signing secret/fingerprint/identity is assumed or committed**. The operator enrollment file is `config/android-consumers.json` (0600, ignored by Git). The operator must never `git add -f` that file or upload it to a bug report. Certificate SHA-256 itself is public identity material but still belongs in reviewed local policy rather than guessed source.

The single-run RM-16 implementation is `actions_android_release.release_batch_build`, `release_batch_import`, and `release_batch_sign`. The local helper `scripts/bkx-devtool-release.py` delegates to the installed Devtool CLI and does not implement an alternate GitHub build, artifact importer or signer. It checks enrollment before each action; if profile selection differs, the operation is refused. Native `--json` responses are the execution receipts; no wrapper-fabricated successful execution receipt is emitted.

## Deployment on Android Termux

1. Apply the source overlay to a **clean, committed** BKX repository. Keep any existing local LIFE credentials untouched.
2. Inspect the existing identity without modifying files:

   ```sh
   cd "$HOME/Code/bkx"
   python3 scripts/bkx-devtool-life.py inspect
   ```

   If Devtool has multiple authorized release identities, pass `--identity ACTUAL_LIFE_ID` to `inspect`, `enroll`, `verify`, and the release helper. Use the actual ID displayed by your installed Devtool LIFE registry; do not invent one. If the existing identity is not eligible for both variants, stop rather than generate a replacement or change the certificate.

3. Enroll once, with explicit consent:

   ```sh
   python3 scripts/bkx-devtool-life.py enroll --yes
   python3 scripts/bkx-devtool-life.py verify
   git status --porcelain
   ```

   The local file is an exact, privately stored Devtool contract generated from REL-05, including the Embedded required rclone entries and Lite forbidden entries. Registration cannot overwrite a prior policy. The checkout must stay Git-clean for the dispatch; the local policy is ignored. Enrollment does **not** sign anything.

4. Plan and dispatch exactly one GitHub build **after this overlay commit is pushed** to the selected ref:

   ```sh
   python3 scripts/bkx-devtool-release.py plan --ref main
   python3 scripts/bkx-devtool-release.py run --ref main --yes
   ```

   Devtool independently verifies the local `HEAD`, clean source, remote ref SHA and registered workflow. A resumed dispatch is not evidence of a newly sent GitHub request; use the actual `run_id`, `run_attempt`, and artifact IDs from GitHub rather than assuming `dispatch_count` truth on unpatched RM-16 deployments.

5. From one **successful** GitHub run, retrieve the exact, independent Embedded and Lite *numeric artifact IDs*. Check and only then import (replace the sample values, never run with placeholders):

   ```sh
   python3 scripts/bkx-devtool-release.py check \
     --run-id RUN_ID --source-sha EXACT_40_HEX_GIT_SHA \
     --embedded-artifact-id EMBEDDED_ARTIFACT_ID --lite-artifact-id LITE_ARTIFACT_ID --download

   python3 scripts/bkx-devtool-release.py import \
     --run-id RUN_ID --source-sha EXACT_40_HEX_GIT_SHA \
     --embedded-artifact-id EMBEDDED_ARTIFACT_ID --lite-artifact-id LITE_ARTIFACT_ID \
     --download --yes
   ```

   Both import candidates must satisfy the **strict two-root-member manifest contract** and match one source, attempt, workflow, repository, APK identity and variant policy. A failed preflight mutates nothing. Once either import has materialized, partial recovery must use its exact `registry_id` rather than re-import blindly.

6. From import JSON, copy the **two distinct immutable unsigned registry IDs** (not artifact IDs). Explicit local sign, never GitHub sign:

   ```sh
   python3 scripts/bkx-devtool-release.py sign \
     --embedded-registry-id EMBEDDED_UNSIGNED_REGISTRY_ID \
     --lite-registry-id LITE_UNSIGNED_REGISTRY_ID --yes
   ```

   Devtool first checks both candidates and the same LIFE certificate, then signs each independently and verifies package, signer, ZIP 16 KiB alignment, ELF load segments and required/forbidden native members. If one sign succeeds and the second fails, **retain the first immutable receipt**; do not rerun the whole batch and overwrite it. Diagnose and recover the second using its exact unsigned registry ID through Devtool's individual `release-sign` path. No new LIFE identity should be generated.

7. Capture the Devtool `--json` batch import and sign receipts as separate files (redact environment-only paths if externally sharing). Optionally cross-check them:

   ```sh
   python3 scripts/bkx-audit-rm16-receipts.py \
     --import-json /path/to/import.json --sign-json /path/to/sign.json
   ```

   The receipt auditor checks matching run, SHA, attempt, two distinct signed digests, common authorized certificate and false release-qualification flags. It is **not** a substitute for `apksigner`, native binary verification, installation or the real Skybind device tests. All incomplete or inconsistent receipts fail closed.

## Explicit promise audit

| Promise | Source evidence | Status |
|---|---|---|
| Exact two consumers, shared producer | REL-05 immutable `config/bkx-android-unsigned-artifacts.json`; `bkx-devtool-life.py` maps that to Devtool's complete consumer schema | **DELIVERED in source**, real local enrollment pending |
| Private signer is reused, not created or uploaded | `_signing_profile(root, target='bkx_android', variant=...)` and `_keystore`, both variants must match exactly; registry Git-ignored 0600; no CI signing secrets added | **DELIVERED in source**, real identity resolution pending |
| Native Devtool schema is authority | `_official_validate` exercises `select_consumer`, `contract_sha256` on both candidate policies; runtime uses Devtool's registry again | **DELIVERED**, tested with mock signer only |
| One build/run, independent immutable APKs | RM-16 `release-batch-plan/run/check/import/sign`, explicit IDs, exact `source_root`; wrapper delegates to Devtool, no local Gradle build | **DELIVERED in source**, remote run pending |
| Correct batch consent and fail-closed tamper protection | `--yes` for run/import/sign; certificate mismatch, tracked/symlink policy, duplicate and missing IDs refused | **DELIVERED**, targeted negative tests passed |
| Post-sign provenance/verification | Native Devtool local sign verifies package, cert, archive entries, 16 KiB ZIP/ELF; offline `bkx-audit-rm16-receipts.py` crosschecks emitted JSON | **PARTIAL**, real signed pair still pending |
| Cross-variant Android installation and Skybind R1 | Planned REL-07, depends on signed pair, trusted device and fresh authorized Skybind grant | **OPEN** |
| Final release qualification | REL-08 only after trustworthy device and external evidence; source scripts never promote | **OPEN** |

## Validation and known limitations

Source tests: `PYTHONPATH=<Devtool source> python3 scripts/test-bkx-rel06-handoff.py`; `python3 scripts/verify-bkx-rel06-handoff.py`. They use a disposable Git repository and fake signer profiles for *identity policy tests*, but invoke the real Devtool consumer contract parser and the RM-16 batch consistency predicate. They do not access personal signing files and do not dispatch GitHub. A real Termux setup can differ (installed Devtool version, LIFE identity variant eligibility, private file permissions, native SDK tools, API credentials). If enrollment refuses to run, preserve the existing LIFE registry and inspect its legitimate identity/target configuration. Never work around refusal by hardcoding a guessed certificate or lifting validation.

**Do not merge REL-07 into this overlay:** REL-07's meaningful evidence requires real signed APK bytes and installed-device/Skybind acceptance. It can provide test harness improvements after REL-06, but cannot be called complete on fixture signatures.
