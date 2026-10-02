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
- ktlint is applied to all modules (root `build.gradle.kts`) — follow the surrounding style (4-space indent, standard Kotlin) and leave it at that; the lint tasks flag plenty of pre-existing upstream violations, so a red `ktlintCheck` is no reason for a formatting crusade.
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
| Open-loop pen suggestions | `plugins/aps/.../loop/LoopPlugin.kt` — `presentPenBolusSuggestion` (`@VisibleForTesting`), pure math in companion `penBolusSuggestion()` (tested in `LoopPluginTest`). **Sized from `APSResult.insulinReq`** — never from a U/h quantity (see lessons). Capped by `DoubleKey.MdiMaxBolusSuggestion` (default 4 U) + `applyBolusConstraints` + Max IOB, floored to the 0.5 U pen step, 60-min throttle. Dismisses on `APSResult.predictedLow`. |
| MDI APS cap overrides | `core/objects/aps/MdiApsProfile.kt`, applied at `OapsProfile` construction in `OpenAPSSMBPlugin` / `OpenAPSAMAPlugin` / `OpenAPSAutoISFPlugin` when `isMDI()` |
| **MDI no-temp-basal enforcement** | MDI must never create a TBR / zero-temp / EB record. Enforced at: `SafetyPlugin.isClosedLoopAllowed` (refuses MDI — LGS included), `LoopPlugin.allowedNextModes` (drops `CLOSED_LOOP`/`CLOSED_LOOP_LGS`/`DISCONNECTED_PUMP`/`SUPER_BOLUS` — one gate that also covers the watch menu + tile), `LoopPlugin.goToZeroTemp`/`suspendLoop` (skip `commandQueue` calls, keep the running-mode record), `SmsCommunicatorPlugin` (`BASAL`/`EXTENDED`/`PUMP DISCONNECT` → `smscommunicator_not_available_mdi`), `DataHandlerMobile.doBolus`/`doFillBolus` (record-only path), `LocalAlertUtilsImpl.checkPumpUnreachableAlarm` (early return). `PumpType.MDI` declares **no** `tbrSettings`/`extendedBolusSettings` (only `PumpCapability.MDI` = Bolus) so no code can treat it as TBR-capable. Tests: `SafetyPluginTest.closedLoopIsDisabledForMdi` / `mdiHasNoPumpLimitOnBasalRate` / `mdiHasNoPumpLimitOnPercentBasalRate` |
| Fixed-basal modeling (MDI) | `OapsProfile.basal_adjustment_allowed` (default `true` = pump unchanged; `!isMDI()` at construction). SMB/AMA/AutoISF engines never scale working basal with the sensitivity ratio; ZT prediction curve uses plain `iobTick.activity` (not `iobWithZeroTemp`); `carbsReq` gets no zero-temp credit. MDI pref label `dynisf_adjust_sensitivity_title_mdi`. Tested in `DetermineBasalSmbMdiTest` |
| Lantus (basal) recording | `ui/.../InsulinDialog.kt` — `TE.Type.NOTE` records `"Lantus xU ..."`, regex lookback 7 days, profile basal auto-rewrite on **any** dose change via `ProfileSource.currentProfile()` + `storeSettings()` + `createProfileSwitch()`; profile-sum fallback compares against the rate-quantized dose (`flatRateTotal`) so an already-flat profile doesn't re-trigger |
| Injection position ("pos") | `core/interfaces/pump/InjectionPosition.kt` (+ test). Dialogs pass **both** `getBolusesFromTimeToTime(3d)` and `getTherapyEventDataFromTime(3d, TE.Type.NOTE)` to `findLastPosition` — forgetting a source silently breaks lookup. Keep PRIMING + NOTE-type filters |
| Status lights (MDI) | `plugins/main/.../StatusLightHandler.kt` + `overview_statuslights_layout.xml` + `OverviewFragment.updateTime()` |
| Meal macro assistant | `core/objects/wizard/MealMacroPlan.kt` (math, unit-tested in `MealMacroPlanTest`) + `WizardDialog` fat/protein rows + `BolusWizard.commonProcessing` eCarbs scheduling + settings in `OverviewPlugin` (`meal_macro_settings`, `meal_*` keys). Upfront % derates **only the meal-carb insulin** (`MealMacroPlan.upfrontCarbInsulin`; corrections/COB/IOB untouched, explicit wizard % wins); fat eq = `MealFatPercentageTenths` % of fat grams spread over `fatDurationH` (duration doesn't change total grams) |
| Hypo-treatment button | `ui/.../CarbsDialog.kt` + `dialog_carbs.xml` + `IntKey.OverviewHypoTreatmentCarbs` (0 = hidden) |
| Autotune (always on) | `plugins/aps/.../autotune/` — upstream gated by `enable_autotune` flag file; fork hard-enables in `ConfigImpl`. MDI "Lantus (U)" row in `AutotuneFragment.showResults()` |
| BG-quality / data spacing | `AutosensDataStoreObject.detectDataSpacing()` (`AutosensDataStore.DataSpacing` tri-state), `BgQualityCheckPlugin` |
| Derived I:C (stats + overview) | `TDD.icRatio` (`core:data` model, null = not calculable) + `icRatioText(rh)` in `implementation/.../TotalDailyDoseExtension.kt` (IC column in the `stats()` table, gated on `includeCarbs`) + `OverviewFragment.icDialogText()` — tap on `cob_layout` (top info row, like the IOB tap) → `OKDialog` **Spanned** message: today's I:C from `tddCalculator.calculateToday()`, profile value via `getIcTimeFromMidnight(secondsFromMidnight)` (NOT `getIc()` — that routes through dynamic-IC plugins), diff with colored arrow (`rh.gac` + `ForegroundColorSpan`). Strings `ic_estimate_*` in plugins/main |
| MDI UX cleanup | `ActionsFragment`, `OverviewFragment`, `OverviewPlugin`, `MyPreferenceFragment`, `InsulinDialog`, `CarbsDialog`, `TreatmentDialog` — each has `isMDI()` branches |
| Objectives unlocked | objectives pre-marked accomplished on start |

## Hard-won lessons (repeat offenders)

- **`rh.gs(id, args)` swallows formatting errors** (falls back silently, logs to Crashlytics). A placeholder/arg mismatch won't crash — it renders wrong. Always count `%n$` placeholders vs args at the call site. And `R.string.x` from the **wrong module compiles fine but resolves the wrong resource** — check which module's `strings.xml` you added the string to.
- **Strings:** English only (Crowdin handles translations). Prefer new short strings over editing long ones shared with AAPSClient (they're translated). Log-only format strings can be `translatable="false"`.
- **Kotlin interface default params are invisible to Java callers** (`@JvmOverloads` is illegal on interface methods). Adding a defaulted param to a core interface breaks Java call sites (e.g. Omnipod Eros). Keep the default and update Java call sites explicitly.
- **`dateUtil.computeDiff` decomposes** the duration (days/hours/minutes are separate components) and returns **nullable** Longs. Reading only HOURS+MINUTES wraps at 24 h. Fold days in: `hours = (diff[DAYS] ?: 0L) * 24 + (diff[HOURS] ?: 0L)` (see `TE.age()`).
- **`firstOrNull { predicate }` + nullable mapper** silently falls back to the first non-match. Use `firstNotNullOfOrNull { mapper }`; for the element itself: `firstNotNullOfOrNull { el -> mapper(el)?.let { el } }`.
- **Hidden prefs can be load-bearing.** MDI hides several APS prefs, but the caps were still read into `OapsProfile` — override values at the point of use (`MdiApsProfile`), never mutate stored prefs.
- **`Constraint.setIfSmaller(limit, reason)` adds the reason whenever `limit < originalValue`, even if it does NOT clamp** (it only clamps when `limit < currentValue`). So `SafetyPlugin` prints "Limiting max basal rate to 500 U/h because of pump limit" *alongside* a 2 U/h hard-limit clamp. Don't assert on reason lists without accounting for this — and don't assume a listed limit was the binding one.
- **`?: 0.0` as a "missing limit" fallback is a trap — it means "limit to 0", not "no limit".** `SafetyPlugin.applyBasalConstraints` had `tbrSettings()?.maxDose ?: 0.0`; nulling MDI's `tbrSettings` would have zeroed the pen suggestion (`tempExtraUnits = (rate − basal) × 0.5`). Use `?.let { }` so absence means unlimited. Same trap exists in `APSResultObject` (androidTest) — harmless there because zero-temp returns earlier.
- **Pen-suggestion sizing must never come from a U/h quantity.** It originally used `(result.rate − basal) × 0.5`, which equals `insulinReq` by construction (`rate = basal + 2*insulinReq` in the engines) — but `result.rate` there is the *constrained* rate, clamped by `applyBasalConstraints` to `min(max(ApsMaxBasal, maxDailyBasal), 4×basal, 3×basal)`. With the `ApsMaxBasal` default of **1.0 U/h** and a flat profile that fallback tops out at **1/3 U** — below the 0.5 U pen step, so it floors to 0 and can never size a suggestion on its own. Size from `insulinReq` instead.
- **`result.smb > 0` is a trap as a "should we bolus" gate.** `rT.units` (→ `smb`) is assigned in exactly one place — inside `if (microBolusAllowed && enableSMB)` — and `microBolusAllowed = isSMBModeEnabled()` chains to `SafetyPlugin.isClosedLoopAllowed()`. That constraint means "*may* closed loop run" (dev build, active EB, MDI) — **not** "is the running mode open loop"; the OPEN_LOOP running mode only selects the notification branch. Until `isClosedLoopAllowed()` started refusing MDI, SMB mode stayed enabled and the engine emitted real `smb` values in open loop (computed, never enacted) — that is what fed the original pen-suggestion notifications, the feature was never dead. Since the MDI refusal `smb` is *always* 0 in MDI, so an `if (result.smb > 0)` gate is dead code **today** — which is exactly why the suggestion now sizes from `insulinReq` unconditionally.
- **`predictedLow` is the only low guard the pen path has.** The engine's `if (enableSMB && minGuardBG < threshold) enableSMB = false` never runs in MDI (reason above). `RT`/`APSResult` now carry `predictedLow`, set unconditionally in `DetermineBasalSMB`/`DetermineBasalAutoISF` (`minGuardBG < threshold`) and `DetermineBasalAMA` (`minPredBG < threshold`); `presentPenBolusSuggestion` dismisses on it. Note AMA computed `insulinReq` locally and never exposed it — it does now (`rT.insulinReq`).
- **`TestBaseWithProfile` formats `rh.gs(id, args)` as `String.format(rh.gs(id), arg)`.** So a 2-arg `gs` call NPEs ("String.length() because s is null") unless you also stub the **1-arg** `gs(id)` with the format template — e.g. `whenever(rh.gs(R.string.bolus_suggestion_text)).thenReturn("Bolus %1\$s U now")`. This is also why reason strings appear fully formatted in test assertions.
- **`determineCorrectBasalSize`/`determineCorrectExtendedBolusSize` throw `IllegalStateException` on null settings** (`PumpTypeExtension.kt`) — now null-safe, but anything consuming `tbrSettings()`/`extendedBolusSettings()` must handle absence.
- **In unit tests a `PumpDescription()` behaves like `resetSettings()` values (`isTempBasalCapable=true`, `tempBasalStyle=PERCENT`), not the field initialisers.** Consequence: the ABSOLUTE pump-limit block is skipped while the PERCENT one runs — that's why `SafetyPluginTest.basalRateShouldBeLimited` sees only the hard-limit reason but `percentBasalRateShouldBeLimited` also sees the `500.00 U/h … pump limit` reason. Set `isTempBasalCapable = false` explicitly to exercise the MDI path.
- **`BaseSeries.getHighestValueY()` returns 100d for an empty series** (not 0) — guard div-by-zero accordingly. Never make `HeartRateDataPoint`/`StepsDataPoint` scale-aware: series are built once but shared across secondary graphs, and per-graph scale multipliers make them vanish.
- **Injection position:** the dialog hint shows "prev x", but the persisted note format **must stay `"pos x"`** — `InjectionPosition.POSITION_REGEX` parses it. Never change `appendToNotes`/`stripPosition`.
- **XML style:** roughly match upstream (attribute-per-line is the local convention) so diffs stay small for rebases — that's the whole point. Don't reformat upstream code, but don't turn formatting into a chore either.
- **Editor tooling gotchas:** large multi-edit replacements on one file can land conflicting intermediate states — split into sequential single edits and re-read the file after a failed edit before retrying. `git mv` with long paths can fail oddly in fish — use `bash -c` with absolute paths.
- **Tests:** `MealMacroPlanTest` uses real meals as golden cases (frozen pizza 131 C/47 F/53 P; 2× Big Mac 82 C/54 P/50 F) — **verify expected values numerically before pinning** (truncation/rounding order matters). `InjectionPositionTest` guards the PRIMING/NOTE filters. `LoopPluginTest` covers `penBolusSuggestion` math. `DetermineBasalSmbMdiTest` covers the fixed-basal split. Pure math belongs in testable companion functions/data classes.
- **`DetermineBasalSMB`/`AutoISF` reuse one mutable `consoleLog`/`consoleError` list across runs** — a second `determine_basal` call overwrites the first RT's log. Snapshot `rt.consoleLog` per run before invoking again in tests.
- **Threshold comparisons: OR/AND inversion traps.** "Rewrite when diff > 1 U OR diff > 10 %" inverts to tolerance `min(...)` for the no-rewrite check — `max(...)` silently requires exceeding BOTH and kills one rule for doses below the crossover. The Lantus rewrite shipped with `max` and 8→9 U (12.5 %) slipped through. Lesson: derive the no-trigger condition algebraically, don't eyeball it; when the spec is "any change", just use `!=` instead of a tolerance band at all.
- **MDI wizard path:** when `pump.isMDI()`, `BolusWizard.commonProcessing` persists carbs/bolus directly (`persistenceLayer`, `Action.EXTENDED_CARBS` for tails) instead of `commandQueue.bolus()`. Any new feature that schedules eCarbs must mirror this MDI branch. Superbolus is skipped in MDI; "record only" is forced in `TreatmentDialog`.
- **`BolusWizard.doCalc` percentage wiring:** `totalPercentage` is only consumed when `usePercentage` is true — the unchecked path uses the positional `percentageCorrection` param. New default-path percentage logic must go through a dedicated path (like `MealMacroPlan.upfrontCarbInsulin`), not `totalPercentage` — routing the meal-plan % there made it display-only.
- **Pen-suggestion sizing:** full `insulinReq` (not `insulinReq/2`), `DoubleKey.MdiMaxBolusSuggestion` cap (default 4 U), 0.5 U pen-step floor (round **down**), 60-min throttle from the last *shown* suggestion. (`BooleanKey.MdiFullInsulinReqSuggestion` was removed Sep 2026.) `ApsMaxSmbFrequency` does **not** suppress suggestions — it only zeroes `result.smb`, which sizing ignores; post-bolus suppression is soft (recent bolus → IOB up → `insulinReq` down) + the throttle. The pref is hidden in MDI (`OpenAPSSMBPlugin`/`OpenAPSAutoISFPlugin` pref screens). `Max IOB` is the cumulative safety bound. Note the suggestion also passes `applyBolusConstraints` → `SafetyMaxBolus` (default **3 U**) + hard limit + bolus-step rounding.
- **`Profile.getIc()` routes through dynamic-IC APS plugins** (`aps.supportsDynamicIc()`). For the *profile's* configured I:C use `getIcTimeFromMidnight(secondsFromMidnight)` — same block math, no dynamic override. Also: `OKDialog.show` has a `Spanned` overload — colored dialog text (`ForegroundColorSpan` + `rh.gac(context, R.attr...)`) needs no custom dialog layout.
- **Editor/tooling:** `multi_replace` checks oldString uniqueness against the *original* file state — two identical blocks each needing the same edit must be sequential calls (second one only matches after the first disambiguated).
- **README.md is the user-facing feature doc** — update it when adding/altering a fork-visible feature.

## Working checklist for a change

1. Locate the feature in the **fork feature map** above; read the related memory-style notes in `AGENTS.md` before coding.
2. Make the smallest additive change; follow existing patterns (`isMDI()` gating, `persistenceLayer` persistence, keys in `core:keys` + `AllowedPreferenceKeys` for openHumans export, settings UI in `OverviewPlugin`/`MyPreferenceFragment`).
3. New user-visible text → `strings.xml` of the **owning module**; new prefs → `core/keys` with validators (`emptyAllowed`, min/max explicitly when custom `validatorParams`).
4. Compile-check the right flavored task; for core-interface changes run `:app:compileFullReleaseKotlin`.
5. Add/extend unit tests for pure math; run the affected module's `testFullDebugUnitTest`.
6. Validate XML with the editor's error check; keep diffs to upstream files purely additive.
