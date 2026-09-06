# BKX 1.0.0 — Go migration audit seal (GO19)

BKX 1.0.0 now has a fully Go-owned CLI and embedded Android engine while retaining the Kotlin/Compose Android UI and protocol-v1 compatibility boundary. Python is no longer present in active source, packaging, CI, Devtool, release, or Android runtime paths.

The final audit freezes the 16-leaf CLI contract, Android operation/handler counts, run-history compatibility, arm64-v8a + NDK29 policy, root/local runtime separation, and deterministic release checksums. The historical Python implementation remains only as a deterministic evidence archive.
