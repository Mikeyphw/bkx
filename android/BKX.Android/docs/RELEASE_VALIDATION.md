# BKX Android release validation

The normal release validation is driven by `.devtool.toml`. BR37 replaces the old long explicit `--task` list with optimized aggregate tasks so split validation does not rerun release packaging, release lint, or Go-backed release-evidence work during cheap preflight. The behavioral phase still owns the full Android unit suite so regressions are fixed instead of hidden.

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

The first task proves that BR37 no longer pulls the full BR36 release graph into the apply smoke gate. The second task compiles unit-test sources, runs `:app:testDebugUnitTest` exactly once through the BR37 behavioral aggregate, and then runs the BR28-BR30 behavioral seals. The unit suite is part of the validation contract so stale or flaky regressions are fixed rather than bypassed.

The split Devtool phase layout is now:

```text
preflight  -> :app:bkxBr37StaticPreflightSeal
unit-test  -> :app:bkxBr37BehavioralUnitSeal
package    -> :app:bkxBr37PackageAndReleaseLintSeal
lint       -> :app:lintDebug
```

Release lint remains required through `bkxBr37PackageAndReleaseLintSeal`; it is not repeated in the standalone lint phase. The cumulative BR21-BR36 final audit task remains available as `:app:bkxBr36FinalAuditSeal` when the full release lane is explicitly desired.

The repository-side cumulative cloud/product replay is:

```bash
./scripts/verify-ov56-roadmap-seal.sh
```

The physical-device lane remains conditional through Devtool instrumentation policy. Run `scripts/qualify-device.sh` on hardware before distributing a release build; that script is the release qualification entry point for protocol/runtime boundary evidence.
