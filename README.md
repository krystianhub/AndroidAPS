# AndroidAPS — MDI fork

This is a personal fork of [AndroidAPS](https://github.com/nightscout/AndroidAPS) (based on latest upstream `master`) with changes targeted at **MDI users** (multiple daily injections, pen users running the app in "virtual pump" mode). This fork makes the app genuinely useful as a decision-support tool with pens.

> ## ⚠️ WARNING — READ THIS FIRST
>
> **These changes were vibe-coded for personal purposes only.** They have been heavily reviewed by an experienced developer, but they are **not** tested to upstream standards, **not** reviewed or endorsed by the AndroidAPS project, and come with **no guarantees of correctness or safety whatsoever**.
>
> This software controls insulin dosing decisions. Bugs here can have serious health consequences. **Use it entirely at your own risk.** If you are not prepared to read the code and fully understand every change yourself, do not use this fork.

## What's different from upstream

### 1. Open Loop works in MDI mode

- The APS engine (recommendations, predictions, DynamicISF) now runs in MDI/virtual-pump mode instead of being blocked outright — closed-loop enactment remains blocked, since a pen cannot accept temp basals.
- In Open Loop, APS suggestions that would normally be a temp basal or SMB are converted into an **actionable manual bolus suggestion**: rounded *down* to the pen's minimum step (under-dosing is safer than over-dosing), throttled to at most one suggestion per 30 minutes, and delivered as a system notification + Overview notification. Basal reductions are not administrable with a pen and are dismissed instead.
- Zero-temp (ZT) prediction lines are hidden on the graph in MDI mode — a pen cannot execute a zero temp, so the line is meaningless.

### 2. Injection position tracking ("pos" feature)

- Injections can be tagged with a **site position 1–12** (like hours on a clock). The position is stored as a `pos x` note on the treatment record, so it persists locally and syncs to Nightscout.
- The Insulin, Treatment and Wizard dialogs show the position field (MDI only, opt-in via *Show position in dialogs*), display the last used position, and pre-suggest the next one in rotation.
- Position history is also used to visualize site rotation.

### 3. Recording long-acting (basal) insulin — Lantus

- The Insulin dialog has a **"Record basal insulin (MDI)"** checkbox: it records the Lantus dose as a Note therapy event (`Lantus 10.0U ...`) — no bolus record, so TDD/IOB are not inflated — and syncs it to Nightscout.
- The recorded dose is compared against the **last recorded Lantus dose** (parsed from previous notes, 7-day lookback; falls back to the profile's basal total). If it differs by more than 1 U or 10 %, the local profile's basal is rewritten to a flat `dose / 24` U/h rate, a profile switch is activated, and a notification confirms the change.
- This keeps the profile basal honest, which matters: TDD in MDI mode includes the *assumed* profile basal, so DynamicISF and TDD-based autosens stay accurate only if the profile matches the actual Lantus dose.

### 4. Overview status lights for MDI

- Pump-specific lights (cannula age, insulin age, reservoir, battery) are **hidden in MDI mode**; sensor age stays.
- New MDI-only lights: **last bolus ago** (`2h 15m`, colored by the configured insulin's action curve — green near peak, red when worn off) and **last basal insulin ago** (time since the last recorded Lantus injection, colored by the Lantus curve — red when overdue).
- The same last bolus / last basal insulin age also appear as rows in the Actions tab stats.

### 5. Autotune enabled

- The Autotune plugin is always available (upstream hides it behind a hidden flag file). Note: with no temp basal records it assumes the profile basal was delivered exactly — reasonable here since the Lantus feature keeps the profile in sync, but sanity-check the tuned basal against your Lantus notes.

### 6. MDI-aware UI cleanup

Gated on the pump being configured as **MDI** (`Pump.isMDI()`, not the coarse `is VirtualPump`):

- **Actions tab**: "Actions" card hidden when no action button applies.
- **Insulin dialog**: eating-soon TT hidden; "Record basal insulin (MDI)" prefills the last Lantus dose; the redundant "record only" checkbox is hidden (in MDI every bolus is record-only).
- **Carbs dialog**: all "Start xxx TT" checkboxes hidden; new 🍬 **hypo treatment** button adds a configurable carbs amount (default 4 g, e.g. one glucose chew) and prefills a "hypo treatment" note — set the amount to 0 to hide the button.
- **Preferences**: BT watchdog, pump-unreachable alert, prime/fill settings, pump status-light thresholds, partial bolus wizard, superbolus, LGS threshold — hidden. SMB/DynISF settings kept (they still shape suggestions).
- **Loop mode icon** on the Overview is visible in MDI mode — the only entry point to the Loop dialog (needed to switch to Open Loop).

### 7. Objectives unlocked

- All objectives are marked as accomplished on start, so no functionality is gated behind the tutorial. The objectives screen remains as an informational checklist.

### 8. Housekeeping

- Removed upstream Git-blocked build restrictions; added a devenv (Nix) development environment.
- BG quality check: sources delivering regular sub-5-minute readings (e.g. Juggluco/Libre 2 at 1 min) no longer trigger the "Recalculated data used" warning — data spacing is classified and dense-but-regular data is treated as clean.

## Safety

This fork relaxes some upstream safety gates (objectives, loop-in-MDI) and is intended for **personal use by an experienced MDI user**. See the warning at the top of this file.

## Suggested OpenAPS/SMB settings for MDI

These are **starting points to validate against your own data, not medical advice** — adjust with your diabetes team's input.

### Two gotchas that matter most for MDI

The APS math is identical for pumps and pens; only enactment differs (SMB → bolus suggestion, temp basal → extra bolus units). But two settings interact badly with the Lantus-derived flat basal (≈ `Lantus dose / 24` U/h, e.g. ~0.8 U/h for 20 U):

1. **SMB size is capped by `profile basal × Max-minutes-of-basal-to-limit-SMB`.** At the default 30 min and 0.8 U/h basal, SMB suggestions cap at 0.4 U → round down to zero with a 0.5 U pen step → **no suggestions at all**. This must be raised.
2. **The temp-basal→bolus conversion path is capped by `Max u/h temp basal` and the two advanced multipliers** (default: current basal × 4, daily basal × 3). With 0.8 U/h basal those caps are ~2.5–3.3 U/h → max ~1.2 U extra per suggestion. Fine for small corrections, limiting for big ones.

### Settings to change from defaults

| Setting | Default → Value | Why |
| --- | --- | --- |
| **Max minutes of basal to limit SMB** | 30 → **120 (max)** | The single most important change (gotcha #1). 120 min × 0.8 U/h ≈ 1.6 U cap per suggestion |
| UAM max minutes | 30 → **120 (max)** | Same math for unannounced meals |
| **Max u/h temp basal** | 1.0 → **~5 U/h** | Allows ~2 U corrections via the temp→bolus path: (5 − 0.8) × 0.5h ≈ 2.1 U |
| Current basal safety multiplier | 4 → **~6** | Default 4 × 0.8 = 3.3 U/h caps corrections at ~1.2 U; 6 × 0.8 = 5 U/h aligns with Max basal |
| Max IOB | 0 → **2–3 U** | Caps cumulative suggestion size; tune to comfort |
| Autosens | ON → **OFF** (if DynISF on) | DynISF takes precedence; running both is redundant |

Everything else (Enable SMB, SMB-with-X triggers, UAM, DynISF, SMB frequency, target adjustments, carbs threshold) works fine at defaults.

### Honest limitations

Even fully tuned, MDI suggestions are **smaller and slower** than a pump loop's corrections: SMB caps at ~1.6 U, suggestions are throttled to one per 30 minutes, and basal *reductions* are never suggested (can't be done with a pen). Expect a conservative advisor, not a loop. If suggestions feel consistently too timid, the levers are `Max-minutes-of-basal` (already at max) and `Max IOB` — not the multipliers.

## Building

Standard AAPS build (Gradle 9, flavors `full` / `aapsclient` / `pumpcontrol`):

```
./gradlew :app:assembleFullRelease   # or use Android Studio
```

For module-level compile checks during development: `./gradlew :ui:compileFullDebugKotlin`.
