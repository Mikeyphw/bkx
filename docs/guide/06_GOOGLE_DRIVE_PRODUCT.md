# Google Drive product integration

BKX exposes Google Drive as a first-class Android backup/archive/restore destination on top of the structured rclone transport boundary.

## Authorization boundary

The Android app uses Google Play services `AuthorizationClient` and requests only `https://www.googleapis.com/auth/drive.file`. BKX does not request the full `drive` scope. The authorization result is converted into the reserved app-managed rclone remote `bkx-google-drive`; the access token is written only to the app-private `files/bkx/rclone/rclone.conf` and is never included in the Android UI model, run history, bridge envelopes, or diagnostics.

Before every operation that targets `rclone://bkx-google-drive/...`, Android refreshes authorization. If Google requires user consent, the operation does not start until the user explicitly completes the Google resolution flow. Disconnect revokes the Drive grant and removes only the BKX-managed section; imported rclone remotes are preserved.

BKX uses the official stable `com.google.android.gms:play-services-auth:21.6.0` lane. Google Play services can return an access token silently when the saved account still has the requested grant, or a `PendingIntent` when user selection/consent is required.

## Cloud layout

The reserved location is:

`rclone://bkx-google-drive/BKX`

The `BKX` folder is intentionally app-owned. With `drive.file`, the app can create and manage files it creates or that the user explicitly authorizes without receiving blanket access to the user's Drive.

A newly connected account can have no `BKX` folder yet. Archive indexing treats that state as an empty library; the first remote backup creates the parent path through rclone.

## Backup publication

Remote backup creation keeps archive generation local and transactional, then publishes each archive part to the remote destination. Split sidecars are uploaded before the logical base file so archive discovery never sees a complete-looking base while parts are missing.

The destination is preflighted for collisions. BKX never overwrites an existing cloud archive. On cancellation, upload failure, or final verification failure, every object published by the current operation is deleted.

When verification is enabled, BKX downloads the completed logical archive back through the same credential-free remote URI, validates archive structure, and compares the logical SHA-256 to the locally generated archive. Transport success alone is not accepted as backup success.

## Archive and restore

`archive.list`, `archive.info`, `archive.browse`, and `archive.verify` accept configured `rclone://` locations. Read operations materialize the requested archive into a temporary private directory, including contiguous `.partNNN` split sidecars, and clean that staging directory after the operation.

Restore uses the same materialization boundary before the existing local restore safety engine runs. Remote storage therefore does not bypass traversal checks, collision review, overwrite confirmation, root destination checks, or archive verification.

## Setup requirements

Because the authorization is an Android OAuth client flow, a personal build must have Google Drive API access and an Android OAuth client registered for package `com.mikeyphw.bkx.android` with the signing certificate used for that APK. Debug and release signing certificates are distinct registrations when their SHA fingerprints differ.

The Android manifest includes `android.permission.INTERNET`; rclone is executed inside the BKX app sandbox and therefore depends on the app owning network permission.

## Deliberate limits

- BKX does not store a Google password.
- BKX does not request full Drive access.
- BKX does not expose an OAuth refresh token because the Android Authorization API owns grant refresh; Android refreshes the short-lived access token before managed Drive operations.
- Background cloud operations that require fresh interactive consent fail closed and ask the user to reconnect instead of opening an unattended authorization UI.
- Encrypted imported `rclone.conf` files remain unsupported until BKX has an explicit config-password lifecycle.
