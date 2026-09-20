# CCT-06 — Chroot Create UI/UX

Portrait Create now exposes chrootctl as a first-class source without treating a rootfs as a filesystem path. The source chooser reports readiness truthfully, queries the typed `chroots.list` bridge, and shows only installed chroots. Selecting a chroot canonically switches the method to chrootctl native export.

The method card explains the ownership boundary: chrootctl creates the chroot archive; BKX verifies and publishes it. Destination selection continues to use BKX local, managed Google Drive, and rclone-folder destinations. Review identifies the native method, while preview and execution route through `ChrootctlExportCoordinator` and surface its progress/result text.

No shell command, arbitrary argv, or raw rootfs backup path is exposed by the UI.


CCT-06 v2 hardens source transitions: only installed inventory records can be selected at the ViewModel boundary, an inventory containing only non-installed records truthfully renders the empty-installed state, and changing from a chroot back to a filesystem source automatically leaves the chrootctl-only engine so the Create draft never gets stranded in an invalid method/source pairing. Draft changes also invalidate the cached native preview.
