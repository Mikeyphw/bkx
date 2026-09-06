# GO19 final migration/release audit

GO19 reconciles the complete HG01–GO18 roadmap against the active Go/Kotlin repository. The machine-readable evidence is `migration/audit/v1/final_audit.json`; `internal/audit` checks it against live Go registries and Android production source.

## Sealed behavior

- Public CLI: 23 frozen command nodes, 16 leaf commands, seven root/global parameter families.
- Android protocol v1: 15 advertised operations, 16 handlers including non-advertised `run.cancel`, and the frozen capability floor.
- Run history: Python-era canonical JSONL remains readable and writable by the Go store.
- Android production: Kotlin/Compose UI, direct `go-native-android` runtime, separate `go-local`/`go-root` state, arm64-v8a only, NDK `29.0.14206865`.
- Release: host CLI + Android ARM64 runtime with sorted `SHA256SUMS`; fixed-commit builds are byte reproducible.
- Repository ownership: active source/build/test/package paths are Go/Kotlin only. The archived Python bundle is historical evidence and is not executed.

## Known constraints

Remote URI transfer remains fail-closed, `ui start` requires an external UI plugin, Android is intentionally arm64-v8a-only, and physical-device instrumentation is conditional on a usable attached device. None of these are migration regressions.

## Termux acceptance

Run the production Go seal with:

```sh
bash ./scripts/validate-go19.sh
```

Run the Android lane through Devtool when Android SDK/Gradle are available:

```sh
devtool --copy -r "$HOME/Code/bkx" --target bkx_android validate
```

Instrumentation remains `when-device-present`; lack of a connected device does not fabricate a pass.

## GO20 retirement set

The audit identified only three migration-era scaffolding groups that can now be retired without touching production behavior or frozen evidence: hidden `migration ...` CLI probes, the `go-native-migration` bridge constructor/identity, and phase-numbered validator wrappers after a canonical maintenance validator replaces them.

## GO20 disposition

All three GO20 retirement candidates identified by this audit were retired. The canonical maintenance seal is now `scripts/validate.sh`; the frozen contracts, oracle, archive, and this audit remain preserved evidence.
