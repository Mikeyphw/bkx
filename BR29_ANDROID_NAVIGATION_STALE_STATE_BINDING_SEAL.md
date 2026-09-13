# BR29 — Android Navigation and Stale-State Binding Seal

**Overlay 9 of 16** in the BKX A01-C17 deduped remediation roadmap.

## Purpose

BR29 closes the Android navigation/state-binding class of findings by making asynchronous UI results request-bound, route-bound, and entity-bound before reducers mutate state. The overlay focuses on stale archive, snapshot, filesystem, backup-plan, restore-preview, transfer-preview, and deep-link/navigation state hazards after the direct domain/runtime contracts from BR21-BR28 are in place.

## Delivered contracts

- Structured `BkxRouteKey` model for top-level destinations plus nested Backup, Archives, and More routes.
- `BkxDeepLinkCodec` for deterministic BKX deep-link routing instead of unstructured destination changes.
- `BkxResponseBinding` that gates result delivery by request id, route key, route generation, entity identity, and draft fingerprint when applicable.
- ViewModel route generation increments for destination, nested route, archive detail, and deep-link navigation changes.
- Backup, restore, and transfer preview results are ignored when the user edits the draft after the request was issued.
- Failed preview requests clear the executable preview only when the failure belongs to the currently displayed draft.
- Archive info/browse/verify responses are ignored when selection or route generation changed.
- Archive refresh reconciles a stale selected archive and resets detail/browse/verification state when the archive disappears from the refreshed library.
- Snapshot list/browse/diff/restore responses are repository/snapshot/path-bound before state mutation.
- `MainActivity` dispatches `ACTION_VIEW` BKX deep links into the typed navigation model.
- Navigation/stale-state contract tests lock the new route/binding model.

## Validation

This is an intermediate overlay. Apply with `--no-validate`; run BR29 validation later in a gate overlay with:

```bash
:app:verifyBkxAndroidNavigationStaleStateBindingSeal
:app:testDebugUnitTest
:app:lintDebug
```
