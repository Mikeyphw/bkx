# GO20 post-migration maintenance baseline

GO20 is not another backend migration. It converts the completed GO19 audit into the long-term repository shape.

Retired: hidden `migration ...` command probes, the `go-native-migration` bridge identity/constructor, and the GO17/GO18/GO19 validator wrapper chain.

Retained: public Go CLI, `contract check`, Android `android-runtime bridge-session`, immutable GO00 contracts, GO17 compatibility oracle/data, GO16 historical reference archive, GO19 audit evidence, Kotlin/Compose Android UI, root/local Go runtime separation, and deterministic release tooling.

The canonical repository validation command is now:

```sh
bash ./scripts/validate.sh
```

Android build/lint/instrumentation remains available through the `bkx_android` Devtool target with instrumentation conditional on a connected usable device.
