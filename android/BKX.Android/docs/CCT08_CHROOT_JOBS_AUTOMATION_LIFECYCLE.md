# CCT-08 — chroot jobs, automation and lifecycle

BKX stores chroot exports as `OperationSpecV2(operation="chrootctl.export")`. The spec contains only the chroot identity and BKX destination identity/path; credentials remain referenced/owned by existing BKX destination configuration.

Every unattended execution re-probes root/chrootctl readiness and re-queries `chroots.list`, requiring the configured chroot to still be installed. Managed Google Drive authorization is refreshed at execution time when its rclone root is selected. Nested rclone destination paths are preserved byte-for-byte by the saved-job contract.

The Android automation engine recognizes chroot jobs before delegating to the Go saved-job runner and executes them through `ChrootctlExportCoordinator`, preserving CCT-03 preview, cancellation, reconciliation, staging and publication guarantees. JobService cancellation cancels the coroutine; CCT-03 then forwards cancellation to the persistent chrootctl operation when an operation id exists.

`chrootctl.import` is interactive-only. Scheduler reconciliation refuses import jobs, and the automation executor refuses them again at execution time. This defense in depth prevents schedules, Tasker and manually forged saved-job dispatches from performing unattended imports.


Automation cancellation is request-aware for native chroot exports. While a chroot Saved Job is executing, the automation request id is mapped only to its coroutine Job. `AutomationAction.Cancel` cancels that coroutine directly; CCT-03's cancellation handler then forwards cancellation to the persistent chrootctl operation once its operation id is known. The mapping is removed in `finally` and carries no credentials or payload data.
