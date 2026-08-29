# ARC-072 — ConfigSnapshot read-model + composition root (live plan)

**Status:** ACTIVE 2026-08-29 · Planned by Fable · Supersedes the R3/R5 sections of the archived
`docs/history/audits/remediation/5-architecture.md` where they disagree (this doc re-derived the
ground truth at `3f92dfe0`; the archived plan's premises had drifted).

## Ground truth re-derived (why the archived R3 needed re-planning)

- R2 (null-safe `globalConfig()`) is DONE — `_globalConfig!!` survives only in a comment.
- R4 (dir-only reorg) and R6 (`Predictor` interface + working fake) are DONE.
- `ConfigSnapshot` does NOT exist anywhere. Static `Config.globalConfig()` consumers: **33 files**.
- The archived plan assumed hot paths hammer `Config.globalConfig()` statically. **False today**:
  `Gesture.kt` (141 ln) and `GestureClassifier.kt` (65 ln) have 1 static read each;
  `Pointers.kt` (1,895 ln) takes `_config: Config` by constructor and reads **36 fields** through
  it; `Keyboard2View.kt` caches `globalConfig()` once into `_config` and reads **28 fields**.
- Therefore the actual hazard is not the static accessor: it is that `Config.refresh()` mutates
  157 `@JvmField var`s **in place** while an active gesture/frame reads them — a torn-read
  window on fold-state change, settings change, or rotation mid-swipe. The snapshot's job is
  **atomic per-gesture / per-frame consistency** first, testability second, static-consumer
  reduction third.

## Design decisions (binding for implementers)

1. `prefs/ConfigSnapshot.kt` — immutable `data class` containing **exactly the union of fields
   the four hot-path files read** (derive mechanically: every `_config\.<field>` in Pointers +
   Keyboard2View, every `globalConfig().<field>` in Gesture + GestureClassifier). Expect ~30–45
   fields, NOT all 157.
2. **Field names mirror `Config`'s snake_case verbatim.** Migration then is a mechanical
   `_config.x` → `snap.x` with zero rename risk. The R7 camelCase pass stays opportunistic and
   is explicitly NOT done here.
3. `Config` gains `@Volatile var snapshot: ConfigSnapshot`, rebuilt (a) at constructor end and
   (b) at the END of `refresh()`. One small allocation per config change — never per keystroke.
4. **Capture discipline** (the point of the whole exercise):
   - `Pointers`: constructor takes a `snapshotProvider: () -> ConfigSnapshot` alongside (not yet
     replacing) `_config`; capture ONE snapshot at pointer-DOWN and use it for the whole
     gesture's decisions. A refresh mid-gesture then lands on the NEXT gesture.
   - `Keyboard2View`: keep `_config` for the write/side-effect paths (the view still writes
     prefs — that residue is a later slice), but per-frame `onDraw`/measure reads capture one
     snapshot at frame start.
   - `Gesture`/`GestureClassifier`: plain parameter/constructor threading — they are leaves.
5. Guardrails carried over from the archived plan, still binding: do NOT freeze `Config`, do NOT
   delete/rename the 157 `var`s, no big-bang consumer migration, no DI framework.

## Ratchet (the enforcement, drift-test idiom)

`ConfigSnapshotRatchetTest` (pure JVM, root package, registered in `pureTestClasses`):
- Per-file zero-pin: migrated hot-path files must contain **zero** `Config.globalConfig()`
  reads and (once fully migrated) zero direct `_config.<field>` reads in the gesture/draw paths
  covered by their slice. The pin list grows per slice — slice 1 pins Gesture + GestureClassifier,
  slice 2 adds Pointers + Keyboard2View.
- Global ceiling: `rg -l 'Config\.globalConfig\(\)' src/main/kotlin` file count **≤ 33**, ratcheted
  down as slices land. Regressions (new static consumers) go red immediately.

## Slices (each independently shippable, TDD-gated)

- **Slice 1 DONE** (`caee60dc`): `ConfigSnapshot` (28 fields) + `Config.snapshot` rebuilt at
  refresh() tail; `Gesture` (per-gesture constructor capture) + `GestureClassifier` (per-call
  arg; Context param deleted) migrated; `ConfigSnapshotRatchetTest` ceiling 33→31.
- **Slice 2 DONE** (`b081ee5c`): per-POINTER capture at `onTouchDown` (each finger captures its
  own snapshot — a latched modifier pseudo-pointer must not freeze config unboundedly, so no
  inheritance); `Keyboard2View` per-unit capture in onMeasure/onDraw/geometry paths. Slice 1's
  union missed `_config?.` reads — snapshot 28→35 fields (`swipe_trail_*`, slider-speed).
  **`Config.edit {}`** added: the sanctioned direct-mutation form — applies the write, bumps
  `version`, republishes via the single `publishSnapshot()` write site; the
  `InputBehaviorSection` stale-write hole is closed at the write site and
  `noDirectWriteToASnapshotMirroredConfigField` reds any future one. Ceiling 31→30 (Pointers'
  two static slider-speed helpers deleted). Owed: instrumented T13
  (`configChangeMidGesture_doesNotAffectTheGestureInFlight`) runs on the next ew-cli pass;
  later-slice residue recorded: `Theme.Computed(_theme, _config, …)` per-measure, androidTest
  direct-write baseline blocks (GeometricSwipeOracleTest:211, PipelineCharacterizationTest:169).
- **Slice 3 (R5 + ARC-098 fold-in):** retire the 6 `*Initializer` files (841 ln) into
  `wiring/KeyboardComponentGraph` (lazy-built, dependency-ordered, one readable file); move the
  4 kept Bridges into `wiring/` in the same change (they remain — genuine delegation seams).
  `onCreate` becomes graph construction + reads. Verification: existing pure/mock gates plus the
  next full ew-cli run (service wiring is not pure-JVM-testable); schedule accordingly.
- **Later (recorded, not scheduled):** Keyboard2View pref-write/startActivity extraction
  (ARC-072 append), WordPredictor decomposition behind the existing `Predictor` seam,
  SettingsActivity's 123 `mutableStateOf`.

## TDD notes for implementers

The refactor-shaped fail-first artifact is the RATCHET test: written first, it fails while the
hot files still read the global/mutable path, and goes green only when the slice's migration is
real. Behavior tests (snapshot-driven Gesture/GestureClassifier) additionally fail-first at the
API level (the constructors don't accept a snapshot yet). Run:
`sh gradlew runPureTests -PtestClass=ConfigSnapshotRatchetTest` then full gates.
