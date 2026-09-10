# Restic remote repositories

BR07+BR08 extends the Restic snapshot engine with named local and cloud repository definitions. A repository definition stores topology and stable credential references only; provider OAuth tokens, passwords, refresh tokens, and rclone configuration contents are never embedded in jobs or run snapshots.

## Named repositories

```bash
bkx repository add phone-local \
  --location "$HOME/BKX-Restic" \
  --credential-ref phone-restic-password

bkx repository add phone-drive \
  --remote drive:BKX/restic/phone \
  --credential-ref phone-restic-password

bkx repository list
bkx repository show phone-drive
bkx repository init --ref phone-drive
```

A named repository can be selected by backup commands without repeating its backend location:

```bash
bkx backup plan --repository-ref phone-drive ~/Documents
bkx backup create --repository-ref phone-drive ~/Documents
```

Saved Job V2 operations can set `repository_ref`; execution resolves the current repository definition while the job remains free of provider credentials.

## Remote execution

BKX stores remote Restic locations in canonical `rclone://REMOTE/PATH` form. At execution time the Restic adapter translates that to Restic's rclone backend and pins the rclone child process to BKX's selected rclone executable. If `BKX_RCLONE_CONFIG` is set, its path is passed through `RCLONE_CONFIG`; the config contents are not copied into operation arguments, saved jobs, run history, or diagnostic metadata.

Direct provider repository strings such as `s3:`, `sftp:`, and native `rclone:` are rejected by BKX. Configure the provider in rclone, then reference it through `rclone://...` instead. This keeps one remote credential boundary for CLI and Android.

## BKX-managed Google Drive

Android already owns a reserved app-private rclone remote named `bkx-google-drive`, rooted at:

```text
rclone://bkx-google-drive/BKX
```

Create the matching repository from the CLI with:

```bash
bkx repository add phone-drive \
  --managed-google-drive \
  --path restic/phone \
  --credential-ref phone-restic-password
```

The `credential-ref` above is for the Restic repository password. Google Drive OAuth remains in BKX's app-private managed rclone configuration and is refreshed by the Android authorization layer before a managed-Drive repository request executes.

The Android repository request contract carries only `repository_ref` plus the non-secret `repository_backend` hint. That hint lets the app refresh managed Google Drive authorization without placing the remote URI or OAuth token in the saved job/request.

## Bridge compatibility

The v1 embedded bridge advertised-operation registry remains frozen. BR07+BR08 repository and snapshot operations are additive extension operations: they are executable by the embedded runtime and exposed through capabilities, but do not rewrite the migration-v1 advertised operation list.
