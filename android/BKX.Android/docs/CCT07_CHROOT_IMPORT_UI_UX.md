# CCT-07 — Chroot import UI/UX

BKX exposes chroot import as a dedicated Backup route rather than disguising it as an ordinary archive restore. The portrait flow is Archive → Import choices → Inspection → explicit confirmation.

Archive selection supports local files and configured custom rclone remotes through the existing filesystem browser. BKX acquires the object into app-owned staging and delegates inspection and import semantics to the CCT-04 `ChrootctlImportCoordinator`.

Inspection displays the suggested chroot identity, distro/release/version, architecture, compressed and expanded sizes, checksum status, metadata fidelity, warnings and blockers. A `legacy_unverified` archive requires an explicit legacy-metadata opt-in and a new preview; compatibility is never silently enabled.

Conflict handling is explicit (`rename` or `overwrite`) and import strategy is explicit (`auto`, `staged`, or `streaming`). Any changed choice invalidates the prior preview and disarms confirmation.

Import is a two-action destructive gate: the user first arms the reviewed preview, then presses **Import chroot**. It cannot be started implicitly or by the ordinary restore flow. Native progress is surfaced while chrootctl runs. If safe root-side staging cleanup cannot be proven, BKX exposes the opaque retained staging ID and a guarded cleanup retry rather than claiming cleanup succeeded.

## v2 audit hardening
- BKX-managed Google Drive is included in the same remote archive browser as imported rclone profiles; it is no longer filtered out of chroot import.
- Invalidating an inspected import by changing archive/name/conflict/strategy/legacy consent performs guarded cleanup of the old root staging through CCT-04. If cleanup cannot be proven, the opaque staging ID is surfaced for Retry safe cleanup rather than silently orphaning it.
