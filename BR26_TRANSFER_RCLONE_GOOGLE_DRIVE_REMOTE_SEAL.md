# BR26 — Transfer, Rclone and Google Drive Remote Seal

Overlay 6 of 16 in the BR21-BR36 remediation roadmap.

## Scope

BR26 closes the transfer/rclone/Google Drive root-fix units:

- U15 — Transfer and rclone remote/topology correctness.
- U16 — Google Drive authorization, refresh and remote integrity.

## Contracts delivered

- `transfer.run` requires authoritative `transfer.plan` evidence: matching `plan_request_id` plus stable `plan_token`.
- Transfer previews expose bounded executable mappings and truncation metadata instead of only a human summary.
- Automation transfer replay decodes the backend `transfer.plan` artifact and no longer fabricates an executable preview.
- Sync/mirror transfers carry an explicit `sync_delete_limit` and rclone receives it through `--max-delete`.
- Transfer checksums must be supported algorithms or complete hex digests.
- Rclone operations fail closed when an Android-managed `BKX_RCLONE_CONFIG` does not contain the requested remote section.
- Rclone progress/failure diagnostics are sanitized and propagated as warnings without leaking token/config values.
- Google Drive remote readiness is a first-class state: missing config, missing managed remote, missing token, or ready.
- UI/automation managed-Drive operations use the shared managed remote detection and refresh path.

## Deferred gate

This is an intermediate overlay and should be applied with `--no-validate`. A later gate overlay should run:

```bash
:app:verifyBkxTransferRcloneGoogleDriveRemoteSeal
:app:testDebugUnitTest
:app:lintDebug
```

Focused Go checks used while preparing this overlay:

```bash
go test ./internal/models ./internal/rcloneops ./internal/transferops ./internal/validation
```
