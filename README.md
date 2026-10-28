# AndroidAPS — MDI fork

Personal fork of [AndroidAPS](https://github.com/nightscout/AndroidAPS) (upstream `master`) aimed at **MDI users** — pen users running the app with the virtual pump. Makes the app usable as a decision-support tool with pens.

> ## ⚠️ WARNING
>
> **Vibe-coded for personal use.** Reviewed by an experienced developer, but not tested to upstream standards, not endorsed by the AndroidAPS project, and with **no guarantee of correctness or safety**. This software influences insulin dosing — bugs can hurt you. **Use entirely at your own risk.** If you won't read and understand every change yourself, don't use this fork.

## What's different from upstream

### Open loop in MDI

- APS engine (recommendations, predictions, DynISF) runs in MDI mode; upstream blocks it for pens.
- **No temp basal, zero temp or extended bolus, ever.** Closed loop and LGS are refused (open loop only); disconnect/superbolus not offered; SMS `BASAL`/`BASALPCT`/`EXTENDED`/`PUMP DISCONNECT` rejected; pump-unreachable alert disabled. Every treatment is record-only.
- Corrections become **manual bolus suggestions** sized from `insulinReq` (not the SMB half), subject to insulin constraints, Max IOB and the pen cap. Rounded **down** to the pen step, max one per 60 min, delivered as notifications. Suppressible below a minimum size, switchable off, optionally sized from a median `insulinReq`. Basal reductions and any suggestion during a projected low are dismissed.
- **"Carbs required" alert also fires in open loop** (hypo projected within 15 min), auto-dismissed when no longer needed, re-raised at most every 10 min.
- Zero-temp prediction lines hidden on the graph.

### Injection position ("pos")

Site position 1–12 stored as a `pos x` note on the treatment (syncs to Nightscout). Insulin/Treatment/Wizard dialogs show the field (opt-in via *Show position in dialogs*), display the last position and pre-suggest the next in rotation.

### Lantus recording

- Insulin dialog checkbox **"Record basal insulin (MDI)"** records the dose as a Note (`Lantus 10.0U ...`) — no bolus, so TDD/IOB aren't inflated.
- On any dose change the profile basal is rewritten to a flat `dose / 24` U/h and a profile switch is activated (compared on the quantized rate, so re-recording the same dose doesn't retrigger). Keeps TDD/DynISF honest.
- Profile screen **"Sync basal"** button (MDI only) rewrites the profile without activating it; enabled only when out of sync.
- **Missed Lantus reminder** (Overview settings, MDI only): urgent notification when no Lantus injection has been recorded for 24 h + grace period after the last one, repeating at the configured interval until a dose is recorded. Does nothing when no Lantus dose was recorded in the lookback window. Toggle: **Missed Lantus reminder (MDI)**; configurable **grace period** (default 120 min), **re-raise interval** (default 60 min) and **lookback** (default 5 days).

### Overview status lights

Pump lights (cannula, insulin, reservoir, battery) hidden; sensor age stays. New MDI lights: APS row with **insulinReq** (current / median over a configurable window) and **lowest predicted → eventual BG** (colored by the projected dip), **last bolus ago** (colored by the insulin curve) and **last basal insulin ago** (colored by the Lantus curve). Last bolus/basal ages also appear in the Actions stats.

### APS plugins

- **OpenAPS SMB, AMA and AutoISF** all selectable in MDI (upstream hides AutoISF behind dev mode). Recommended: **SMB + dynamic sensitivity**; AutoISF is experimental.
- Dynamic sensitivity hides/ignores the classic autosens toggle.
- **Fixed-basal modeling**: the working basal is never scaled by the sensitivity ratio, and no prediction pretends Lantus can be suspended (zero-temp curve and carbs-required math both assume basal continues).

### Autotune

Always available (no flag file), automation action usable without engineering mode. MDI results table gains a "Lantus (U)" row; save/copy/profile-switch actions never change the profile basal — only the Lantus flow does.

### Meal macro assistant

Wizard gains **Fat** and **Protein** fields (grams). Filling either shows a plan preview:

```
Now 70g @ 63% · Tail 66g @ +60min/4h · Fat-eq 3g @ +90min/8h
```

- **Upfront**: a heaviness score splits carbs into fast/slow and derates **only the fast-carb insulin** between the lean/heavy preferences (defaults 100 % ↔ 60 %). The withheld part is deliberately not dosed; an explicit wizard % overrides it.
- **Tails**: slow carbs + protein equivalents (default 10 % of protein) as eCarbs from +60 min over 4 h; fat equivalents (default 8 % of fat) as a second eCarbs record from +90 min over 8 h. Both feed COB/predictions.
- Meals under 5 g fat *and* 10 g protein skip the assistant (plain wizard).
- Settings in *Overview → Meal macro assistant*, with presets:

| Preset | Protein | Fat | Heavy-meal upfront |
| --- | --- | --- | --- |
| **Conservative** (default) | 10 % / 4 h | 8 % / 8 h | 60 % |
| **Warsaw-style (derated)** | 30 % / 4 h | 28 % / 8 h | 50 % |
| **Warsaw-style 0.7×** | 21 % / 5 h | 20 % / 8 h | 55 % |

> ⚠️ Community heuristics, **not** validated dosing rules. Start Conservative and compare the actual curve against the plan for a few meals before moving up.

### Deferred carb logging

Enable globally in *Overview → Deferred carb logging*, then tick **Defer carbs** in the Bolus Wizard when you want to delay the carb entry. The bolus is logged immediately; the carbs are held back and only recorded when you press **Eat now** on the Overview screen. **Cancel** discards them. Defer carbs and Carb time are mutually exclusive — pick one per wizard run.

While a deferred entry is pending, the **Eat now** / **Cancel** buttons appear on the Overview screen. The entry expires after **1 hour**; if it expires without being confirmed, the Overview shows a notice so the missing carb entry isn't overlooked. Only one entry can be active at a time.

### MDI UI cleanup

Gated on `Pump.isMDI()`:

- **Actions**: card hidden when no action applies.
- **Insulin dialog**: eating-soon TT hidden, last Lantus dose prefilled, "record only" checkbox hidden.
- **Carbs dialog**: TT checkboxes hidden; 🍬 **hypo treatment** button (configurable carbs + note), disabled until an amount is set.
- **Preferences**: BT watchdog, pump-unreachable alert, prime/fill, pump status-light thresholds, partial bolus wizard, superbolus, LGS threshold and pump basal caps hidden. SMB/DynISF settings kept.
- **Loop mode icon** visible — the only way into the Loop dialog.

### Other

- **Objectives** pre-marked accomplished (screen stays as a checklist).
- **Derived I:C**: `IC` column in the Statistics TDD table; tapping the Overview COB icon shows today's I:C vs the profile's, with the difference and a colored arrow.
- Removed upstream Git-blocked build restrictions; added a Nix devenv.
- BG quality check no longer flags regular sub-5-minute sources (e.g. Juggluco/Libre 2 at 1 min).

## Suggested settings for MDI

Starting points to validate against your own data — **not medical advice**.

Pen suggestions are sized from `insulinReq`, never from a U/h rate, so the pump caps (Max u/h basal, multipliers, SMB/UAM max minutes) are irrelevant and hidden in MDI.

| Setting | Value | Why |
| --- | --- | --- |
| **Max pen bolus suggestion (MDI)** | **4 U** (0.5–15) | Pen-specific cap; raise toward 6–8 U only with experience |
| **Min pen bolus suggestion (MDI)** | **0.5 U** | Suppresses smaller suggestions |
| **Pen bolus suggestions (MDI)** | **on** | Master switch for the notifications |
| **Smooth pen bolus suggestions (MDI)** + window | **off**, 15 min | Sizes from the median `insulinReq`; a one-cycle spike gives no suggestion |
| **Missed Lantus reminder (MDI)** | **on** | Urgent reminder when no Lantus dose is recorded 24 h + grace period after the last one; repeats at the configured interval (default 60 min) |
| **Missed Lantus grace period / re-raise interval / lookback (MDI)** | **120 min / 60 min / 5 days** | Grace period added to 24 h, repeat interval, and how far back to look for a recorded dose |
| **Max bolus** (Safety) | **3 U** | Also caps each suggestion — raise it together with the pen cap or the bigger pen cap does nothing |
| **Max IOB** | start **~8 U**, walk down to 5–6 if nights stay flat | Must exceed your upfront meal dose; caps cumulative dosing |
| TDD-based sensitivity ratio | **off** initially | MDI: adjusts glucose targets only, basal never scaled |

Everything else (Enable SMB, UAM, DynISF, target adjustments, carbs threshold) works at defaults.

**Limitations:** at most one suggestion per 60 min, each capped by Max pen bolus suggestion *and* Max bolus, and basal reductions are never suggested. Expect a patient advisor, not a loop. Levers for slow meal tails: raise **both** caps and **Max IOB**.

## Building

Gradle 9, Java 21, flavors `full` / `pumpcontrol` / `aapsclient` / `aapsclient2`:

```
./gradlew :app:assembleFullRelease   # or Android Studio
```

Module compile checks must name a flavor (`compileDebugKotlin` is ambiguous):

```
./gradlew :ui:compileFullDebugKotlin
```

`AGENTS.md` maps the modules, where each MDI feature lives, build/test commands and the gotchas. Read it before changing code.
