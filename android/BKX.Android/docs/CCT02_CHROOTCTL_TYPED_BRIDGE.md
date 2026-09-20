# CCT-02 — typed chrootctl app bridge

Devtool target: **`bkx_android`**. This phase changes Android only; no Go/backend BKX overlay is required.

## Boundary

BKX consumes the installed chrootctl module exclusively through the versioned `chrootctl app` protocol selected by CCT-01. The app/UI cannot submit an executable path, shell fragment, or arbitrary argv. The UID-0 service duplicates the operation allowlist and accepts only:

- `chroots.list`
- `chroots.get`
- `transfer.preview_export`
- `transfer.import_preview`
- `transfer.export`
- `transfer.import`

The root process resolves the executable through `ChrootctlRuntimeResolver` immediately before every bridge invocation.

## Transport

Query and preview calls are bounded JSON responses. Operations use a `ParcelFileDescriptor` pipe and fixed `app run --request-file - --events ndjson` invocation. Binder requests are capped at 256 KiB, captured responses at 1 MiB, and each streamed NDJSON event at 256 KiB.

Operation starts are never automatically replayed after an uncertain transport failure. Persistent chrootctl operation ids are exposed through typed events and can be read, cancelled, or reconciled only through the fixed `app operation`, `app cancel`, and `app reconcile` commands.

## Translation

`ChrootctlBridgeCodec` maps protocol-v1 request/response/error/event envelopes into BKX models and provides typed `ChrootctlChrootRecord` decoding for `chroots.list` and `chroots.get`. Later CCT phases add export/import-specific domain models on top of this transport instead of parsing CLI text.

## Target split policy

If a later CCT phase needs both BKX Go changes and Android changes, it must be emitted as separate target-specific overlays. CCT-02 is Android-only.

## Durable reconciliation identity
BKX persists only the request-to-operation identifier mapping needed to reconcile an interrupted external operation after app/UI process loss. Request JSON, passwords, archive paths, and payload data are never written to this ledger. chrootctl remains authoritative for operation state and journal contents.
