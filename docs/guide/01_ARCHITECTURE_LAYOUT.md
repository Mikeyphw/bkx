# BKX architecture layout

BKX is Go-first in the repository and Kotlin/Compose on Android. The accepted Python
implementation is retained only as a deterministic historical archive under
`migration/archive/` and is not an active source, test, package, Devtool, CI, release,
or Android runtime path.

```text
.
├─ cmd/bkx/                    # production Go CLI
├─ internal/                   # Go engines, services, bridge, contracts, fixtures tests
├─ migration/
│  ├─ contracts/v1/           # immutable historical compatibility contract
│  ├─ fixtures/v1/            # GO17 Go-owned compatibility oracle/data
│  └─ archive/                # deterministic historical Python evidence only
├─ scripts/
│  ├─ build-go-android-runtime.sh
│  ├─ build-go-release.sh
│  ├─ validate-go17.sh        # immutable compatibility + deterministic release seal
│  ├─ validate-go18.sh        # GO18 repository purity + GO17 seal
│  └─ validate-go19.sh        # final migration/release audit seal
├─ build-logic/                # Android verification Gradle logic
├─ android/BKX.Android/        # Kotlin/Compose app; packaged ARM64 Go runtime
├─ go.mod
└─ .devtool.toml              # Go production and Android targets only
```

The protocol-v1 Android bridge and public CLI ultimately call the same Go service
implementation. Optional `bkx-tui` and `bkx-interactive` UIs are external executables,
not embedded alternative runtimes.
