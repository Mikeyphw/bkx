# Apply BKX Android overlays with Devtool

AOV-13→16 embedded the canonical BKX engine. AOV-17→20 added root-native storage and removed Termux execution. AOV-21→24 replaces the developer frontend with the Material 3 product UI and wires the primary workflows to the embedded backend.

Interim mega overlays are applied without full validation:

```bash
devtool --yes --copy -r ~/Code/bkx --target bkx_android \
  apply overlay --no-validate ~/Downloads/<overlay>.zip
```

Full Android validation remains reserved for the AOV-25→32 release-seal overlay. Source-hash inventory conflicts must fail closed rather than applying an overlay to a different repository state.
