# Native zstd archive support

BKX uses `github.com/klauspost/compress/zstd` v1.18.4 directly in its Go runtime.
The CLI and Android embedded Go runtime use the same codec. Normal zstd archive
operations do not invoke a shell, an external zstd binary, or a Python codec.

Supported product paths:

- Create TAR/Bakery archives compressed with zstd, and ZIP archives using method 93.
- Read, list, browse, verify, and restore native zstd archives, including split parts.
- Restore ZIP method 20 from older zstd ZIP writers; new output uses method 93.
- Preserve empty and binary files and reject corrupt compressed frames and ZIP metadata.
- Use the existing configured compression levels 1–22 (mapped to the Go codec's
  supported encoder presets) and single-worker codec concurrency for mobile memory use.
- Use the same archive codec for local backups, rclone-backed archive publication,
  saved jobs, and Android/Tasker backup operations that request zstd.

This is archive support, not a new standalone `zstd` command or dictionary-management UI.
Restic remains its own snapshot engine and manages its own repository compression.

`TestZstdNativeFramesRoundTripWithoutExternalTools` removes PATH and checks real
compression plus byte-identical restore. Independent interoperability tests use the
system zstd executable when available; only that optional external comparison skips
when it is absent. Native zstd tests always run. The final overlay validator rejects
replacement compression modules and runs `go mod verify`, `go test ./...`, and
`go vet ./...` against the declared dependency. Test-only codec substitutes are not
valid release evidence and are never included in an overlay or runtime.
