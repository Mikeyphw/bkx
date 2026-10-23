# BKX Android release validation

The normal release validation is driven by `.devtool.toml`. BR37 replaces the old long explicit `--task` list with optimized aggregate tasks so split validation does not rerun release packaging, release lint, or Go-backed release-evidence work during cheap preflight. The behavioral phase still owns the full Android unit suite so regressions are fixed instead of hidden.

The developer-facing `build`, `release`, and `package` aliases are intentionally separate from those CI-safe validation aggregates. They run `assemblePersonalDebug`, `bkxPersonalReleaseSeal`, and `bkxPersonalPackageAndReleaseLintSeal` respectively, so a local distributable build fails immediately unless the permanent signing material under `~/.config/devtool/bkx` is configured.

For the BR37 overlay/apply smoke gate, use the optimized static graph seal:

```bash
devtool -r ~/Code/bkx --target bkx_android validate \
  --task :app:bkxBr37OptimizedGradleTaskSeal
```

That task intentionally stops at the static preflight graph. It proves the optimized task layout without forcing the unit-test, package, release-lint, instrumentation, or Go-backed package evidence lanes during overlay application.


For BR37 v7 full regression repair validation, run both the optimized graph smoke gate and the behavioral/full-unit lane:

```bash
devtool -r ~/Code/bkx --target bkx_android validate \
  --task :app:bkxBr37OptimizedGradleTaskSeal \
  --task :app:bkxBr37BehavioralUnitSeal
```

The first task proves that BR37 no longer pulls the full BR36 release graph into the apply smoke gate. The second task compiles unit-test sources, runs `:app:testEmbeddedDebugUnitTest` and `:app:testLiteDebugUnitTest` once per edition through the BR37 behavioral aggregate, and then runs the BR28-BR30 behavioral seals. The unit suite is part of the validation contract so stale or flaky regressions are fixed rather than bypassed.

The split Devtool phase layout is now:

```text
preflight  -> :app:bkxBr37StaticPreflightSeal
unit-test  -> :app:bkxBr37BehavioralUnitSeal
package    -> :app:bkxBr37PackageAndReleaseLintSeal
lint       -> :app:lintEmbeddedDebug
```

Release lint remains required through `bkxBr37PackageAndReleaseLintSeal`; it is not repeated in the standalone lint phase. The cumulative BR21-BR36 final audit task remains available as `:app:bkxBr36FinalAuditSeal` when the full release lane is explicitly desired.

The repository-side cumulative cloud/product replay is:

```bash
./scripts/verify-ov56-roadmap-seal.sh
```

The physical-device lane remains conditional through Devtool instrumentation policy. Run `scripts/qualify-device.sh` on hardware before distributing a release build; that script is the release qualification entry point for protocol/runtime boundary evidence.

## REL-00–02 dual-edition source foundation

The default Devtool Android target builds **Embedded debug** for fast local iteration.
GitHub release production will build both `assembleEmbeddedRelease` and
`assembleLiteRelease` in one run; **neither APK is release-qualified merely by
passing Gradle**. The BR37 package seal inspects the exact ZIP contents of
both release editions and verifies their 16 KiB ELF contract. Lite must not
contain `libbkxrclone_exec.so` or its provenance asset, while both editions
must contain the Go runtime `libbkxgo.so`. No Android key is sent to GitHub.

The full edition-aware JVM suite invokes `testEmbeddedDebugUnitTest` and
`testLiteDebugUnitTest`; the source-only smoke gate retains cheap BR37 preflight.
Instrumentation defaults to Embedded and requires separate Lite device evidence.
The dedicated GitHub unsigned producer and Devtool RM-16 artifact registrations
are later roadmap steps and are **not** claimed completed here.

### Current edition-aware task inventory (REL-03 migration evidence)

This inventory supersedes historical unflavored references in older acceptance
reports. Source and CI gates must resolve each registered task for its edition:

- `:app:testEmbeddedDebugUnitTest`, `:app:testLiteDebugUnitTest`
- `:app:lintEmbeddedDebug`, `:app:lintLiteDebug`
- `:app:lintEmbeddedRelease`, `:app:lintLiteRelease`
- `:app:connectedEmbeddedDebugAndroidTest` (Lite device acceptance separate)
- `:app:assembleEmbeddedRelease`, `:app:assembleLiteRelease`
- `:app:verifyBkxEditionApkContents`

Historical seal gate task references retained and still registered:
`:app:verifyBkx16KbNativePageSizeContract`,
`:app:verifyBkxAutomationProductContract`,
`:app:verifyBkxPersonalSigningContract`,
`:app:verifyBkxReleaseMergedManifestContract`,
`:app:verifyBkxReleasePackagingSigningNativeRuntimeSeal`,
`:app:bkxAndroidReleaseSeal`.

## REL-03 regression migration and split 16 KiB proof

The BR35–BR37 gates now require exact APK page-size verification independently:
`:app:verifyBkxEmbeddedRelease16Kb` and `:app:verifyBkxLiteRelease16Kb`.
The prior `:app:verifyBkx16KbNativePageSizeContract` remains an aggregate
that requires both tasks. The BR37 package phase includes both, both release
lint variants, merged manifest checks, and `verifyBkxEditionApkContents`.
CI retains both debug JVM suites and all four debug/release lint reports.
The BR37 cheap preflight never invokes those heavy tasks. See
`docs/release/REL03_VARIANT_REGRESSION_AUDIT.md` for preserved historical
promises and the external Gradle/device qualification work.
