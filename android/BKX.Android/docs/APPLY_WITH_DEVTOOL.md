# Apply BKX Android overlays with Devtool

AOV-13→16 embedded the canonical BKX Python engine. AOV-17→20 adds optional libsu root execution, direct filesystem access, Termux-private storage access, and removes the Termux RUN_COMMAND backend.

Interim mega overlays are applied without full validation:

```bash
devtool --yes --copy -r ~/Code/bkx --target bkx_android \
  apply overlay --no-validate ~/Downloads/<overlay>.zip
```

Full Android validation is reserved for the final release-seal overlay. Source-hash inventory conflicts must fail closed rather than applying an overlay to a different repository state.
