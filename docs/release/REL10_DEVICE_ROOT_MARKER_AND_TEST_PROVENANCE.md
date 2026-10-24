# BKX REL10 — root-safe device retention marker and dynamic instrumentation provenance

## Incident and evidence

The locally LIFE-signed BKX pair from GitHub run **40114502372**, source
`bc6c11297dc23ea545029c3026e894726fb3d90f`, passed GitHub CI, unsigned
import and local signing with the existing certificate. On the physical rooted
Android device, the Embedded build initially failed startup because its Go
`files/bkx/go-local/home` directory was unavailable. The operator repaired
ownership of the app-private `files` directory and reported that startup was
recovered. The actual device UID/ownership diagnostic dump was not attached,
so root ownership from REL07's `mkdir -p` is a strongly supported cause but not
a separately captured device-level proof of origin.

## Fix 1: REL07 must not create an app-private directory as root

Before the first upgrade or retention-marker write, REL07 now:

- Obtains one exact installed BKX UID from Android package manager (UID > 10000).
- Inspects the **existing** app-private `filesDir` via root in read-only mode.
- Rejects missing, symlinked, root-owned, other-UID, or non-owner-writable
  directories; instructs the operator to launch BKX to initialize `filesDir`
  using its own Android application context.
- Never creates `filesDir` or its parent, never recursively `chown`s anything,
  and never repairs arbitrary files on a rooted device.
- Checks the expected app UID across every Embedded → Lite → Embedded swap.
- Creates only a fixed, random-content retention-marker **file** using shell
  no-clobber mode in the already verified app-owned directory, chowns that
  marker file to the app UID, and fails closed on errors; removes a newly
  created marker if setting marker permissions fails.
- Retains the existing `adb install -r` data preservation, SHA/cert/UID checks,
  and truthful partial-device-only results.

If the directory does not exist, simply launch BKX first. REL07 must not
bootstrap it via privileged `mkdir -p`.

## Fix 2: dynamic GitHub run provenance for LIFE-signed androidTest evidence

The matching instrumentation helper no longer embeds historical run
`40045942016` in its evidence. It requires the audited import/sign pair's
verified, positive `run_id` and `run_attempt`, exact 40-hex source SHA,
`receipts_pass=true`, and `qualified_release=false`. Its evidence records that
exact run, attempt, and source; invalid or missing audit fields fail closed.

The existing LIFE signer, production APK bytes, and source checks are unchanged.
Because an overlay changes Git HEAD, REL10 tools cannot claim that production
APKs from earlier SHA belong to the new commit; a freshly built/signed matching
source pair remains necessary for source-specific release qualification.

## Scope and remaining authority

Modified: `scripts/bkx-rel07-qualify.py`,
`scripts/bkx-rel07-build-matching-instrumentation.py`, and their already
reviewed regression suites. Added this document. No production Kotlin/Go,
Gradle configuration, CI workflow, source pin, APK, signing key, or device data
is changed by the overlay.

The regression suite tests missing/root-owned/nonwritable/symlinked preflight
failures, rejection before any APK install, app-UID continuity, no-clobber marker
creation and cleanup, and multiple unrelated current/future audited GitHub
run IDs. Full device instrumentation and Skybind qualification remain
**not run**. `qualified_release=false`.
