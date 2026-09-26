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

### 5. APS algorithm plugins un-gated

- **OpenAPS SMB, AMA and AutoISF** are all selectable in Config Builder in MDI mode (upstream hides AutoISF behind engineering + dev mode).
- **Recommended: OpenAPS SMB + dynamic sensitivity.** AutoISF is the experimental ga-zelle algorithm (dynamic ISF + BG-acceleration/brake modifiers) — its micro-bolus shaping has little leverage in open loop; treat it as an experiment.
- With dynamic sensitivity on, the sensitivity ratio comes from TDD; classic autosens is only a **fallback** for missing TDD data — keep it ON.

### 6. Autotune enabled

- The Autotune plugin is always available (upstream hides it behind a hidden flag file), and the **Run Autotune automation action** is available without engineering mode.
- Note: with no temp basal records it assumes the profile basal was delivered exactly — reasonable here since the Lantus feature keeps the profile in sync, but sanity-check the tuned basal against your Lantus notes.
- **MDI**: the results table gains a "Lantus (U)" row (current dose from the last recorded Lantus injection, tuned 24h basal total, both rounded to whole units). The proposed curve remains visible, but every save, copy, and profile-switch action preserves the input profile's basal; only the Lantus injection/profile-update flow changes MDI basal.

### 7. Meal macro assistant — fat/protein → eCarbs dosing plan

Fatty, slowly-absorbed meals (pizza, burgers, curries) are the classic MDI pain: a single upfront bolus guesses at a carb curve that lasts hours. The bolus **Wizard** now has **Fat** and **Protein** fields (grams). Filling either one activates a dosing plan, shown as a one-line preview above the calculation:

```
Tail 66g @ +60min/4h · Fat 3g @ +90min/8h · upfront 63%
```

- **Upfront part**: a meal-heaviness score (0–1, from normalized fat/protein content) splits the real carbs into a fast part (bolused now) and a slow part, and interpolates the upfront bolus percentage between the *lean* and *heavy* preferences (defaults 100 % ↔ 60 %).
- **Primary tail**: slow carbs + protein equivalents (default 10 % of protein grams) are scheduled as **eCarbs** starting +60 min, spread over 4 h.
- **Fat tail**: fat equivalents (default 1 % of fat grams per hour over 8 h ≈ 8 % total) are scheduled as a second eCarbs record starting +90 min, spread over 8 h.
- On OK both tails are recorded (record-only, like everything in MDI) and feed COB/predictions — the loop then converts the rising glucose predictions into pen-bolus suggestions while the meal is still being absorbed. Protein/fat equivalents also keep autosens from misreading the late rise as insulin resistance.
- **Everything is adjustable before confirming** — the preview is informational, the percentage can be overridden with the existing % checkbox (manual values always win), and zero macros give exactly the upstream wizard.
- **Activation safeguard**: meals with less than 5 g fat *and* less than 10 g protein (both configurable) skip the assistant entirely — plain wizard logic applies, so a splash of oil or a spoon of yogurt can't produce a silly 99 %-upfront micro-plan.

All conversion factors live in *Settings → Overview → Meal macro assistant* and are applied to every new wizard run. Three **preset buttons** overwrite them with community methods, so you can switch approaches without recompiling:

| Preset | Protein | Fat | Heavy-meal upfront |
| --- | --- | --- | --- |
| **Conservative** (default) | 10 % / 4 h | 1.0 %/h / 8 h | 60 % |
| **Warsaw method** | 30 % / 4 h | 3.5 %/h / 8 h | 50 % |
| **Modified Warsaw (0.7×)** | 21 % / 5 h | 2.5 %/h / 8 h | 55 % |

> ⚠️ The conversion factors are community heuristics, **not** validated dosing rules. Start with Conservative, compare the actual glucose curve against the plan for a few meals (each entry is logged with its inputs), and only move toward Warsaw if the tail consistently outlasts the coverage. Sanity-check with your diabetes team.

Calculator math is unit-tested with real meals (frozen pizza 131 C/47 F/53 P, 2× Big Mac 82 C/54 P/50 F) as golden cases — see `MealMacroPlanTest`.

### 8. MDI-aware UI cleanup

Gated on the pump being configured as **MDI** (`Pump.isMDI()`, not the coarse `is VirtualPump`):

- **Actions tab**: "Actions" card hidden when no action button applies.
- **Insulin dialog**: eating-soon TT hidden; "Record basal insulin (MDI)" prefills the last Lantus dose; the redundant "record only" checkbox is hidden (in MDI every bolus is record-only).
- **Carbs dialog**: all "Start xxx TT" checkboxes hidden; new 🍬 **hypo treatment** button adds a configurable carbs amount (default 4 g, e.g. one glucose chew) and prefills a "hypo treatment" note — set the amount to 0 to hide the button.
- **Preferences**: BT watchdog, pump-unreachable alert, prime/fill settings, pump status-light thresholds, partial bolus wizard, superbolus, LGS threshold — hidden. SMB/DynISF settings kept (they still shape suggestions).
- **Loop mode icon** on the Overview is visible in MDI mode — the only entry point to the Loop dialog (needed to switch to Open Loop).

### 9. Objectives unlocked

- All objectives are marked as accomplished on start, so no functionality is gated behind the tutorial. The objectives screen remains as an informational checklist.

### 10. Housekeeping

- Removed upstream Git-blocked build restrictions; added a devenv (Nix) development environment.
- BG quality check: sources delivering regular sub-5-minute readings (e.g. Juggluco/Libre 2 at 1 min) no longer trigger the "Recalculated data used" warning — data spacing is classified and dense-but-regular data is treated as clean.

## Safety

This fork relaxes some upstream safety gates (objectives, loop-in-MDI) and is intended for **personal use by an experienced MDI user**. See the warning at the top of this file.

## Suggested OpenAPS/SMB settings for MDI

These are **starting points to validate against your own data, not medical advice** — adjust with your diabetes team's input.

### How pen suggestions are capped in MDI (differs from pump logic!)

The APS math is identical for pumps and pens; only enactment differs (SMB → bolus suggestion, temp basal → extra bolus units). The upstream SMB caps are designed for a **closed loop dosing every few minutes** — with hourly pen suggestions they would cap each suggestion at ~1.6 U and make the whole feature useless. So this fork changes the cap model:

- **Max pen bolus suggestion (MDI)** — new setting in the OpenAPS SMB screen (MDI only, default **4 U**, range 0.5–15): the real, single lever for how big one suggestion can get.
- **SMB max minutes / UAM max minutes / Max u/h basal / multipliers**: overridden internally in MDI (they'd otherwise cap APS requests at ~0.4–0.8 U) and **hidden** in MDI mode — no longer limit pen suggestions, no need to touch them.
- **Suggestions are throttled to one per 60 minutes** and always rounded **down** to the pen's 0.5 U step (under-dosing is the safe direction).
- **Max IOB stays the true safety bound**: it caps cumulative suggested dosing, exactly as for pumps (each suggestion is also limited to `Max IOB − current IOB`).
- **SMB frequency still applies in MDI**: after any recorded bolus (upfront dose, correction), suggestions are suppressed for N minutes (the "How frequently SMB will be given" setting). Since the pen throttle (60 min) is much longer anyway, keep this at its small default (1–3 min) — never raise it, it only stacks on top.

| Setting | Value | Why |
| --- | --- | --- |
| **Max pen bolus suggestion (MDI)** | default **4 U** | The cap that actually matters now; raise toward 6–8 U only with experience |
| **Max IOB** | start at **~8 U**, walk down to 5–6 if nights stay flat | Must exceed your upfront meal dose (else suggestions are dead for hours after injecting); caps cumulative dosing |
| **SMB frequency** | keep small (**1–3 min**) | Suppresses suggestions after a recorded bolus; raising it only stacks on top of the 60-min pen throttle |
| SMB max minutes / UAM max minutes / Max u/h basal / multipliers | leave at **defaults** (hidden in MDI) | Overridden internally; shape nothing user-visible |
| Autosens | keep **ON** | With DynISF on, autosens is only a fallback for missing TDD data — not redundant |

Everything else (Enable SMB, SMB-with-X triggers, UAM, DynISF, target adjustments, carbs threshold) works fine at defaults.

### Honest limitations

Even fully tuned, MDI suggestions are **slower** than a pump loop's corrections: at most one suggestion per 60 minutes, each ≤ the Max pen bolus suggestion (default 4 U), and basal *reductions* are never suggested (can't be done with a pen). Expect a patient advisor, not a loop. If coverage during a big meal tail feels too slow, the levers are **Max pen bolus suggestion** (bigger single doses) and **Max IOB** (more total headroom).

## Building

Standard AAPS build (Gradle 9, flavors `full` / `aapsclient` / `pumpcontrol`):

```
./gradlew :app:assembleFullRelease   # or use Android Studio
```

For module-level compile checks during development: `./gradlew :ui:compileFullDebugKotlin`.
