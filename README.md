# AndroidAPS — MDI fork

Personal fork of [AndroidAPS](https://github.com/nightscout/AndroidAPS), based on upstream **4.0.0**, aimed at **MDI users** — pen users running the app with the virtual pump. Makes the app usable as a decision-support tool with pens.

> ## ⚠️ WARNING
>
> **Vibe-coded for personal use.** Reviewed by an experienced developer, but not tested to upstream standards, not endorsed by the AndroidAPS project, and with **no guarantee of correctness or safety**. This software influences insulin dosing — bugs can hurt you. **Use entirely at your own risk.** If you won't read and understand every change yourself, don't use this fork.

## What's different from upstream

### Open loop in MDI

- APS engine (recommendations, predictions, DynISF) runs in MDI mode; upstream blocks it for pens.
- **No temp basal, zero temp or extended bolus, ever.** Closed loop and LGS are refused (open loop only); disconnect/superbolus not offered; the Loop action never offers “Accept new temp basal”; SMS `BASAL` (including `%`), `EXTENDED`, `BOLUS`, `TARGET` and `PUMP DISCONNECT` are rejected (a pen cannot deliver, and a remote temp target would be invisible in the MDI UI); pump-unreachable alert disabled. Every treatment is record-only.
- Corrections become **manual bolus suggestions** sized from `insulinReq` (not the SMB half), subject to insulin constraints, Max IOB and the pen cap. Rounded **down** to the pen step, max one per 60 min, delivered as notifications. Suppressible below a minimum size, switchable off, optionally sized from a median `insulinReq`. Basal reductions and any suggestion during a projected low are dismissed.
- **"Carbs required" alert also fires in open loop** (hypo projected within 15 min), auto-dismissed when no longer needed, re-raised at most every 10 min.
- Zero-temp prediction lines hidden on the graph.

### Injection position ("pos")

Site position 1–12 stored as a `pos x` note on the treatment (syncs to Nightscout). The Insulin, Wizard and Treatment dialogs show the field (opt-in via *Appearance → Show injection position in dialogs*), display the last position as a disabled field on the left, and pre-suggest the next in rotation. Wizard, fixed-batch and Lantus confirmations show it as the same coloured **Injection site: X** line.

### Lantus recording

- Insulin dialog checkbox **"Record basal insulin (MDI)"** records the dose as a Note (`Lantus 10U ...`) — no bolus, so TDD/IOB aren't inflated. Lantus pens dial whole units only: the amount step, quick-add presets (1/2/5 U), typed input, prefill and confirmation all snap to 1 U, unlike the 0.5 U rapid-acting pen.
- A recorded dose above **Max Lantus dose (MDI)** (default 30 U) is rejected instead of being recorded — protection against typos like `200` instead of `20`, which would also rewrite the profile basal.
- On any dose change the profile basal is rewritten to a flat `dose / 24` U/h and a profile switch is activated (compared on the quantized rate, so re-recording the same dose doesn't retrigger). An active profile switch (percentage / time shift / remaining duration) is preserved. Keeps TDD/DynISF honest.
- Profile screen **sync icon** (MDI only) rewrites **and saves** the profile being viewed without activating or switching to it — switching remains your decision, so tap *Activate profile* if you want the loop to use the new basal. Enabled when that profile is out of sync, and also when no Lantus dose has been recorded yet (tapping then tells you why there is nothing to sync).
- **Missed Lantus reminder** (*Loop → Lantus (MDI)*): urgent notification when no Lantus injection has been recorded for 24 h + grace period after the last one, repeating at the configured interval until a dose is recorded. Once a reminder cycle has started it keeps reminding even after the last dose falls out of the lookback window (the message then reports the window as the minimum overdue time); a user who never recorded a Lantus dose is not nagged. Toggle: **Enable reminder**; configurable **grace period** (default 120 min), **re-raise interval** (default 60 min) and **lookback** (default 5 days, min 2).

### Overview status lights

Pump lights (cannula, insulin, reservoir, battery) hidden; sensor age stays. The MDI status card shows **Suggested insulin** (current / median over a configurable window), **Predicted BG** (lowest predicted → eventual BG; green when the landing value is in the profile target, orange when it is out of target or dips below target, red when it is outside the 3.9–10.0 mmol/L band or dips below 3.9), **last bolus** (colored by the insulin curve carried by that bolus) and **last basal** (colored by the Lantus curve, shown in whole units). Ages include minutes (`3h 25m`), so a recent dose no longer looks an hour older than it is. The card sits under the status section; its settings are in that section's gear (**Status lights**, where MDI keeps the sensor and MDI groups). Last bolus/basal are **not** duplicated in the Manage sheet.

### APS plugins

- **OpenAPS SMB, AMA and AutoISF** all selectable in MDI (upstream hides AutoISF behind dev mode). Recommended: **SMB + dynamic sensitivity**; AutoISF is experimental.
- Dynamic sensitivity hides/ignores the classic autosens toggle.
- **OpenAPS SMB settings that only affect pump enactment are hidden in MDI**: Max basal rate, LGS threshold, Enable SMB and all its variants, SMB interval, max basal minutes to limit SMB, and UAM max basal minutes. Max IOB, UAM prediction, DynISF/Autosens and carb thresholds stay.
- **Fixed-basal modeling**: the working basal is never scaled by the sensitivity ratio, and no prediction pretends Lantus can be suspended (zero-temp curve and carbs-required math both assume basal continues).

### Autotune

Always available (no flag file): enable it in *Configuration Builder → General → Autotune* (or search “Autotune”). It also has a tile in **Manage**. The automation action is usable without engineering mode. MDI results table gains a **Lantus** row (the 24 h basal sum as whole-unit injections). Applying or auto-switching the results keeps every basal block unchanged — Autotune writes only IC/ISF in MDI; only the Lantus flow changes basal.

### Deferred carb logging

**MDI-only feature.** Enable it in *Bolus Wizard settings → Deferred carb logging* (also searchable in Settings), then tick **Defer carbs** in the Bolus Wizard when you want to delay the carb entry. The bolus is logged immediately; the carbs are held back and only recorded when you press **Eat now** on the Overview screen. **Cancel** discards them. Defer carbs and Carb time are mutually exclusive — pick one per wizard run. Defer carbs also disables the auto e-carbs (Bread/Cake/Pizza) for that run: the whole meal is held, so the two cannot be combined.

While a deferred entry is pending, the Overview shows a banner with the held amount and **Eat now** / **Cancel** buttons. Both actions ask for confirmation first, so a meal is not logged or thrown away by a stray tap. The entry expires after **1 hour**; if it expires without being confirmed, the Overview shows a notice so the missing carb entry isn't overlooked (a deferred entry that cannot be read is also reported instead of vanishing). Only one entry can be active at a time. Discarding or letting it expire does not undo the bolus given at wizard time — log the carbs manually if you already ate.

### MDI UI cleanup

Gated on `Pump.isMDI()`:

- **Temporary targets / temp basals hidden everywhere**: no Overview TT chip action, Manage entry, quick-launch action, wizard toggle, Carbs/Insulin TT checkboxes, or automation action. The Overview still shows the profile/DynISF target as a **read-only** chip (no tap, no editor). Temp basal/extended bolus entries and the TBR chip are gone too.
- **Graphs**: the fixed IOB graph draws no basal band for MDI. Its name is just **IOB** unless you enable the new **COB** overlay (COB gets the right axis), and activity can still be overlaid. Tap the graph name to choose either overlay.
- **Actions / Manage**: card hidden when no action applies; last bolus/basal were removed from Manage and live only in the MDI status card.
- **Insulin dialog**: eating-soon TT hidden, last Lantus dose prefilled, "record only" checkbox and banner hidden, date/time row available to back- or forward-date a pen dose; injection-position card uses the shared previous-site field and picker. Recording basal insulin asks for confirmation first.
- **Wizard**: the “bolus will be recorded” banner and confirmation line are hidden (record-only is the normal MDI behaviour); the TT include button is removed; **Defer carbs** is its own labelled-switch row like Profile, and only the switch itself toggles it.
- **Carbs dialog**: TT checkboxes hidden; the 🍬 **hypo treatment** button sits next to the +10/+20 buttons and **adds** the configured carbs to the current amount.
- **Profile**: the minimal **Sync basal** icon is always visible in MDI (outside edit mode). It updates the profile currently being viewed, not just the active one, and is enabled only when that profile is out of sync.
- **Preferences**: BT watchdog, pump-unreachable alert, prime/fill, pump status-light thresholds, partial bolus wizard, superbolus, Open-loop minimum change, LGS threshold, SMB/pump basal caps and the SMB-only settings listed above hidden. The hidden partial-bolus pref never scales a pen dose — the wizard's own **%** checkbox (and a QuickWizard per-button `%` or the watch wizard's percentage) still works as an explicit per-run choice.
- **Loop mode icon** visible — the only way into the Loop dialog.

### Other

- **Objectives** pre-marked accomplished (screen stays as a checklist).
- **Derived I:C**: `IC` column in the Statistics TDD table, alongside the restored **Basal %** column (basal share of TDD); tapping the Overview COB icon shows today's I:C vs the profile's, with the difference in g/U and percent, coloured green/red by direction.
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
| **Missed Lantus grace period / re-raise interval / lookback (MDI)** | **120 min / 60 min / 5 days** | In *Loop → Lantus (MDI)*: grace period added to 24 h, repeat interval, and how far back to look for a recorded dose |
| **Max bolus** (Safety) | **3 U** | Also caps each suggestion — raise it together with the pen cap or the bigger pen cap does nothing |
| **Max IOB** | start **~8 U**, walk down to 5–6 if nights stay flat | Must exceed your upfront meal dose; caps cumulative dosing |
| TDD-based sensitivity ratio | **off** initially | MDI: adjusts glucose targets only, basal never scaled |

Everything else (DynISF, Autosens, UAM prediction, target adjustments, carbs threshold) works at defaults. SMB enactment settings are hidden because a pen cannot deliver an SMB.

**Limitations:** at most one suggestion per 60 min, each capped by Max pen bolus suggestion *and* Max bolus, and basal reductions are never suggested. Expect a patient advisor, not a loop. Levers for slow meal absorption: raise **both** caps and **Max IOB**.

## Building

Gradle 9.8.2, Java 21, flavors `full` / `pumpcontrol` / `aapsclient` / `aapsclient2` / `aapsclient3`:

```
./gradlew :app:assembleFullRelease   # or Android Studio
```

For a **signed production APK** with the same injected signing as Android Studio/CI:

```
cp .env.example .env      # fill in KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS
./build-release.sh        # full flavor; add a flavor name to override
```

The script reads `.env`, prompts for anything missing (passwords without echo), and prints the APK
path and SHA-256. `.env` is git-ignored.

Compile checks: the KMP library modules have no flavors, so use `compileAndroidMain`; the app is
flavored, so name the flavor:

```
./gradlew :ui:compileAndroidMain              # any library module
./gradlew :app:compileFullDebugKotlin         # app shell
```

`AGENTS.md` maps the modules, where each MDI feature lives, build/test commands and the gotchas. Read it before changing code.
