# AGENTS.md — AndroidAPS (MDI fork)

Guidance for AI coding agents working in this repo. Read this before exploring — it distills verified knowledge about the codebase, the fork's MDI features, and lessons learned the hard way.

## What this repo is

- **Personal MDI fork** of [nightscout/AndroidAPS](https://github.com/nightscout/AndroidAPS) (based on upstream `master`), version 3.4.2.6. Target audience: pen/MDI users running the app with the **virtual pump** ("MDI" pump type) in open loop. See `README.md` for the full feature list and the safety warning.
- **This is safety-critical insulin-dosing software.** Keep diffs minimal, prefer additive changes, and never relax caps/safety logic beyond what is explicitly documented.
- **Rebase discipline:** fork changes are kept as additive as possible so rebases onto upstream stay clean. Avoid reformatting upstream code. Don't rename/move upstream symbols without a strong reason.

## Build & test

- Gradle 9, **Java 21** (`Versions.jvmTarget = JVM_21`), Kotlin, AGP; `compileSdk 36`, `minSdk 31`. Version constants in `buildSrc/src/main/kotlin/Versions.kt`.
- **Flavors** (dimension `standard` in `app/build.gradle.kts`): `full`, `pumpcontrol`, `aapsclient`, `aapsclient2`. Module compile-check tasks must include the flavor:
  - ✅ `./gradlew :ui:compileFullDebugKotlin` — ❌ `compileDebugKotlin` (ambiguous, fails).
  - `:core:keys` is a pure JVM module → `./gradlew :core:keys:compileKotlin`.
- **Full build:** `./gradlew :app:assembleFullRelease` (or Android Studio).
- **Unit tests:** `./gradlew -Pcoverage -PfirebaseDisable testFullDebugUnitTest` (`runtests.sh`), or per module: `./gradlew :core:objects:testFullDebugUnitTest`.
- **Parity tests** (Kotlin APS ↔ JS reference): `app/src/androidTest/.../ReplayApsResultsTest` — needs a device/emulator; run when touching determine-basal logic.
- **Cross-module breakage:** compiling one module in debug does NOT catch breakage in pump plugins (e.g. Java callers of core interfaces). When changing core interfaces, verify `./gradlew :app:compileFullReleaseKotlin`.
- **Environment:** Nix devenv (`devenv shell` — Java + Python). Shell is **fish** (no `for..do..done` — wrap in `bash -c`). `python3` is not reliably on PATH — use `perl` for bulk text edits. `javap`/`xxd` are available in the devenv shell.
- ktlint is applied to all modules (root `build.gradle.kts`). 4-space indent, standard Kotlin style, autoformat changed files.
- Layout/resource-only changes don't need a build — validate with the editor's error check.

## Module map

| Module | Contents |
| --- | --- |
| `app` | Application shell, activities, `MyPreferenceFragment`, androidTest parity fixtures (`assets/determine-basal.js` is **test-only**) |
| `wear` | Wear OS companion app |
| `core:interfaces` | Contracts: `Pump`, `PumpSync`, `DetailedBolusInfo`, `InjectionPosition`, `ProfileSource`, `AutosensDataStore`, … |
| `core:objects` | Domain models + math: `BolusWizard`, `MealMacroPlan`, `MdiApsProfile`, `BS`/`CA`/`TE`, `aps/` result objects |
| `core:data` | DB entities (`BS` bolus, `CA` carbs, `TE` therapy events, `FD` food), mappers incl. Nightscout V1/V3 |
| `core:keys` | Preference keys (`IntKey`, `BooleanKey`, `DoubleKey`, `StringKey`, …) + `AllowedPreferenceKeys` |
| `core:ui` | Shared UI: resources, `rh.gs()` string helper, shared dialog includes (`notes.xml`, `position.xml`, `datetime.xml`, `okcancel.xml`, `number_picker_layout.xml`) |
| `core:utils`, `core:validators`, `core:graph(view)`, `core:libraries`, `core:nssdk` | Helpers, input validators, graphing, vendored libs, Nightscout SDK |
| `database:persistence` / `database:impl` | `AppRepository` / `persistenceLayer` (Room), `insertOrUpdateCarbs/Bolus`, therapy events |
| `implementation` | Impls of core interfaces (e.g. `DetermineBasalResult`) |
| `workflow` | WorkManager workers: `IobCobOrefWorker`, `PrepareTreatmentsDataWorker`, `CarbsInPastExtension`, … |
| `plugins:aps` | **APS engines**: `DetermineBasalSMB/AMA/AutoISF.kt` (Kotlin runtime ports), `LoopPlugin`, `AutotunePlugin`, `BgQualityCheckPlugin` |
| `plugins:main` | Overview/Actions UI, `OverviewPlugin`, `StatusLightHandler`, `QuickWizard` |
| `plugins:insulin`, `:sensitivity`, `:smoothing`, `:source`, `:sync`, `:automation`, `:constraints`, `:configuration` | Other plugin categories (insulin curves, autosens/dynISF, SMB filtering, xDrip/Juggluco/Nightscout sources, NS sync, automation, constraints) |
| `pump:*` | Pump drivers (Combo, Dana, Medtronic, Omnipod, …). `pump:virtual` is the MDI path |
| `ui` | Dialogs: `WizardDialog`, `InsulinDialog`, `CarbsDialog`, `TreatmentDialog`, `DialogFragmentWithDate` |
| `shared:impl`, `shared:tests` | Shared implementations; test helpers (JUnit5/Mockito, `testImplementation(project(":shared:tests"))`) |

## Architecture essentials

- **Runtime APS = Kotlin ports** (`plugins/aps/.../DetermineBasalSMB|AMA|AutoISF.kt`). The `determine-basal.js` under `app/src/androidTest/assets` is a **parity-test fixture only** — "determine-basal.js line N" in discussions is conceptual.
- **Data flow:** source plugins → `workflow` workers (bucketed data → IOB/COB/autosens) → APS plugin (`DetermineBasal*`) → `LoopPlugin` → enactment via `CommandQueue` → pump, or notifications (open loop).
- **UI is XML layouts + DialogFragments** (no Compose in practice). Shared input layouts live in `core/ui/src/main/res/layout/` and are `<include>`d by `ui/.../dialog_*.xml`. Visibility of notes/position rows is controlled in `DialogFragmentWithDate` (prefs `OverviewShowNotesInDialogs` / `OverviewShowPositionInDialogs`).
- **DI is Dagger** (e.g. `PluginsListModule` with `@IntKey(n)` plugin bindings; new plugins need a binding).
- **Record-only (MDI) delivery:** carbs never go to the pump; `CommandQueueImplementation.bolus()` splits carbs out and persists DB-only. In MDI mode boluses are persisted directly via `persistenceLayer.insertOrUpdateCarbs/insertOrUpdateBolus` (bypasses the pump queue and VirtualPump fake-progress UI).
- **eCarbs:** a `CA` row with `duration > 0` and/or future timestamp; `AppRepository.expandCarbs()` expands into 15-min slices feeding COB. One record = linear absorption; multi-bump curves need multiple records.
- **`Pump.isMDI()`** (`core/interfaces/pump/Pump.kt`, `model() == PumpType.MDI`) is the gate for MDI-specific behavior. **Do NOT use `is VirtualPump`** for feature gating — it also matches virtual-pump users emulating real pumps. Generic "no physical pump" checks (BT permissions, pump sync verification) legitimately use `is VirtualPump`. `model()` is only valid after `refreshConfiguration()` (runs in `onStart`).

## Fork feature map (where the MDI code lives)

| Feature | Key locations |
| --- | --- |
| Open-loop pen suggestions | `plugins/aps/.../loop/LoopPlugin.kt` — `presentPenBolusSuggestion`, pure math in companion `penBolusSuggestion()` (tested in `LoopPluginTest`). `APSResult.insulinReq` |
| MDI APS cap overrides | `core/objects/aps/MdiApsProfile.kt`, applied at `OapsProfile` construction in `OpenAPSSMBPlugin` / `OpenAPSAMAPlugin` / `OpenAPSAutoISFPlugin` when `isMDI()` |
| Lantus (basal) recording | `ui/.../InsulinDialog.kt` — `TE.Type.NOTE` records `"Lantus xU ..."`, regex lookback 7 days, profile basal auto-rewrite (>10% or >1 U) via `ProfileSource.currentProfile()` + `storeSettings()` + `createProfileSwitch()` |
| Injection position ("pos") | `core/interfaces/pump/InjectionPosition.kt` (+ test). Dialogs pass **both** `getBolusesFromTimeToTime(3d)` and `getTherapyEventDataFromTime(3d, TE.Type.NOTE)` to `findLastPosition` — forgetting a source silently breaks lookup. Keep PRIMING + NOTE-type filters |
| Status lights (MDI) | `plugins/main/.../StatusLightHandler.kt` + `overview_statuslights_layout.xml` + `OverviewFragment.updateTime()` |
| Meal macro assistant | `core/objects/wizard/MealMacroPlan.kt` (math, unit-tested in `MealMacroPlanTest`) + `WizardDialog` fat/protein rows + `BolusWizard.commonProcessing` eCarbs scheduling + settings in `OverviewPlugin` (`meal_macro_settings`, `meal_*` keys) |
| Hypo-treatment button | `ui/.../CarbsDialog.kt` + `dialog_carbs.xml` + `IntKey.OverviewHypoTreatmentCarbs` (0 = hidden) |
| Autotune (always on) | `plugins/aps/.../autotune/` — upstream gated by `enable_autotune` flag file; fork hard-enables in `ConfigImpl`. MDI "Lantus (U)" row in `AutotuneFragment.showResults()` |
| BG-quality / data spacing | `AutosensDataStoreObject.detectDataSpacing()` (`AutosensDataStore.DataSpacing` tri-state), `BgQualityCheckPlugin` |
| MDI UX cleanup | `ActionsFragment`, `OverviewFragment`, `OverviewPlugin`, `MyPreferenceFragment`, `InsulinDialog`, `CarbsDialog`, `TreatmentDialog` — each has `isMDI()` branches |
| Objectives unlocked | objectives pre-marked accomplished on start |

## Hard-won lessons (repeat offenders)

- **`rh.gs(id, args)` swallows formatting errors** (falls back silently, logs to Crashlytics). A placeholder/arg mismatch won't crash — it renders wrong. Always count `%n$` placeholders vs args at the call site. And `R.string.x` from the **wrong module compiles fine but resolves the wrong resource** — check which module's `strings.xml` you added the string to.
- **Strings:** English only (Crowdin handles translations). Prefer new short strings over editing long ones shared with AAPSClient (they're translated). Log-only format strings can be `translatable="false"`.
- **Kotlin interface default params are invisible to Java callers** (`@JvmOverloads` is illegal on interface methods). Adding a defaulted param to a core interface breaks Java call sites (e.g. Omnipod Eros). Keep the default and update Java call sites explicitly.
- **`dateUtil.computeDiff` decomposes** the duration (days/hours/minutes are separate components) and returns **nullable** Longs. Reading only HOURS+MINUTES wraps at 24 h. Fold days in: `hours = (diff[DAYS] ?: 0L) * 24 + (diff[HOURS] ?: 0L)` (see `TE.age()`).
- **`firstOrNull { predicate }` + nullable mapper** silently falls back to the first non-match. Use `firstNotNullOfOrNull { mapper }`; for the element itself: `firstNotNullOfOrNull { el -> mapper(el)?.let { el } }`.
- **Hidden prefs can be load-bearing.** MDI hides several APS prefs, but the caps were still read into `OapsProfile` — override values at the point of use (`MdiApsProfile`), never mutate stored prefs.
- **`BaseSeries.getHighestValueY()` returns 100d for an empty series** (not 0) — guard div-by-zero accordingly. Never make `HeartRateDataPoint`/`StepsDataPoint` scale-aware: series are built once but shared across secondary graphs, and per-graph scale multipliers make them vanish.
- **Injection position:** the dialog hint shows "prev x", but the persisted note format **must stay `"pos x"`** — `InjectionPosition.POSITION_REGEX` parses it. Never change `appendToNotes`/`stripPosition`.
- **XML style:** one attribute per line, 4-space indent per level (upstream style). IDE collapse of attributes creates huge noisy diffs — re-expand before committing. `git diff` cleanliness vs upstream matters for rebases.
- **Editor tooling gotchas:** large multi-edit replacements on one file can land conflicting intermediate states — split into sequential single edits and re-read the file after a failed edit before retrying. `git mv` with long paths can fail oddly in fish — use `bash -c` with absolute paths.
- **Tests:** `MealMacroPlanTest` uses real meals as golden cases (frozen pizza 131 C/47 F/53 P; 2× Big Mac 82 C/54 P/50 F) — **verify expected values numerically before pinning** (truncation/rounding order matters). `InjectionPositionTest` guards the PRIMING/NOTE filters. `LoopPluginTest` covers `penBolusSuggestion` math. Pure math belongs in testable companion functions/data classes.
- **MDI wizard path:** when `pump.isMDI()`, `BolusWizard.commonProcessing` persists carbs/bolus directly (`persistenceLayer`, `Action.EXTENDED_CARBS` for tails) instead of `commandQueue.bolus()`. Any new feature that schedules eCarbs must mirror this MDI branch. Superbolus is skipped in MDI; "record only" is forced in `TreatmentDialog`.
- **Pen-suggestion sizing:** full `insulinReq` (not `insulinReq/2`), `BooleanKey.MdiFullInsulinReqSuggestion` (default true), `DoubleKey.MdiMaxBolusSuggestion` cap (default 4 U), 0.5 U pen-step floor (round **down**), 60-min throttle. SMB frequency still suppresses after recorded boluses. `Max IOB` is the cumulative safety bound.
- **README.md is the user-facing feature doc** — update it when adding/altering a fork-visible feature.

## Working checklist for a change

1. Locate the feature in the **fork feature map** above; read the related memory-style notes in `AGENTS.md` before coding.
2. Make the smallest additive change; follow existing patterns (`isMDI()` gating, `persistenceLayer` persistence, keys in `core:keys` + `AllowedPreferenceKeys` for openHumans export, settings UI in `OverviewPlugin`/`MyPreferenceFragment`).
3. New user-visible text → `strings.xml` of the **owning module**; new prefs → `core/keys` with validators (`emptyAllowed`, min/max explicitly when custom `validatorParams`).
4. Compile-check the right flavored task; for core-interface changes run `:app:compileFullReleaseKotlin`.
5. Add/extend unit tests for pure math; run the affected module's `testFullDebugUnitTest`.
6. Validate XML with the editor's error check; keep diffs to upstream files purely additive.
