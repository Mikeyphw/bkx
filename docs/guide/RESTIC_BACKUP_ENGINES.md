# BKX backup engines — BR05+BR06

BKX now has an additive backup-engine layer. Existing `backup create` behavior remains the Bakery archive engine unless the caller explicitly selects Restic.

## Portable archives (existing behavior)

```bash
bkx backup create ~/Documents --destination ~/Backups
```

This creates an independent `.bakery` archive and preserves the v1 CLI/bridge compatibility floor.

## Local incremental snapshots

Restic is opt-in:

```bash
export RESTIC_PASSWORD_FILE="$HOME/.config/bkx/restic-phone.pass"
bkx repository init --engine restic "$HOME/BKX-Restic"
bkx backup create --engine restic --repository "$HOME/BKX-Restic" ~/Documents
```

A stable BKX credential reference can be used instead of putting password material in a job or command specification:

```bash
export BKX_CREDENTIAL_FILE_PHONE_REPO="$HOME/.config/bkx/restic-phone.pass"
bkx repository init --credential-ref phone-repo "$HOME/BKX-Restic"
bkx backup create --engine restic --repository "$HOME/BKX-Restic" --credential-ref phone-repo ~/Documents
```

The secret itself is passed to Restic through its supported environment/password-file contract and is never added to Restic command arguments or Saved Job V2 parameters.

Saved jobs use the additive `backup.snapshot` operation:

```bash
bkx job create phone-incremental \
  --operation backup.snapshot \
  --params-json '{"engine":"restic","repository":"/home/me/BKX-Restic","sources":["/home/me/Documents"],"verify":true,"dry_run":false}' \
  --credential-ref phone-repo

bkx job run phone-incremental
```

Historical Restic backup runs are replayable. Their immutable run snapshot records the repository, source/filter/tag configuration, engine, and only the credential reference—not credential material.

## BR06 scope boundary

This stage intentionally accepts **local Restic repositories only**. rclone/cloud repositories, managed Google Drive adaptation, snapshot browsing/diff/restore, retention/forget/prune, and repository maintenance UI are implemented by later BR07–BR14 stages.

The Android core already carries the same `SnapshotBackupSpec` / `RepositoryInitSpec` wire names and advertises the new backend operations, but the user-facing Android repository and snapshot screens are intentionally deferred to BR11–BR14.
