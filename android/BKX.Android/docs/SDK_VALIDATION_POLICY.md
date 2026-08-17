# BKX Android SDK validation policy

BKX Android uses the latest SDK platform that the Termux/chroot validator can install reliably.

Current validation pin:

- Devtool Android platform: `36`
- Gradle `compileSdk`: `36`
- Gradle `targetSdk`: `36`
- Gradle `minSdk`: `23`

Reason: final validation failed before compilation because `sdkmanager` could not resolve `platforms;android-37`, even after Devtool cleared partial SDK downloads and retried. An SDK package that cannot be installed by the validator must not be part of the release gate.

The project keeps the StudyFlow-aligned AGP 9 Gradle/plugin/dependency stack. Only SDK platform selection is pinned to the installable stable platform. Lint version-nudge detectors for newer SDK/dependency availability are disabled so validation does not fail because of unavailable preview or channel-dependent packages.

This policy is grounded in the actual validator result from the BKX Android final gate, not in lint's newest-version availability suggestions.
