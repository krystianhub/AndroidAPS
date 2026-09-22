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

### 2. Injection position tracking ("pos" feature)

- Injections can be tagged with a **site position 1–12** (like hours on a clock). The position is stored as a `pos x` note on the treatment record, so it persists locally and syncs to Nightscout.
- The Insulin, Treatment and Wizard dialogs show the position field (MDI only, opt-in via *Show position in dialogs*), display the last used position, and pre-suggest the next one in rotation.
- Position history is also used to visualize site rotation.

### 3. Recording long-acting (basal) insulin — Lantus

- The Insulin dialog has a **"Record basal insulin (MDI)"** checkbox: it records the Lantus dose as a Note therapy event (`Lantus 10.0U ...`) — no bolus record, so TDD/IOB are not inflated — and syncs it to Nightscout.
- The recorded dose is compared against the **last recorded Lantus dose** (parsed from previous notes, 7-day lookback; falls back to the profile's basal total). If it differs by more than 1 U or 10 %, the local profile's basal is rewritten to a flat `dose / 24` U/h rate, a profile switch is activated, and a notification confirms the change.
- This keeps the profile basal honest, which matters: TDD in MDI mode includes the *assumed* profile basal, so DynamicISF and TDD-based autosens stay accurate only if the profile matches the actual Lantus dose.

### 4. Objectives unlocked

- All objectives are marked as accomplished on start, so no functionality is gated behind the tutorial. The objectives screen remains as an informational checklist.

### 5. Housekeeping

- Removed upstream Git-blocked build restrictions; added a devenv (Nix) development environment.

## Safety

This fork relaxes some upstream safety gates (objectives, loop-in-MDI) and is intended for **personal use by an experienced MDI user**. See the warning at the top of this file.

## Building

Standard AAPS build (Gradle 9, flavors `full` / `aapsclient` / `pumpcontrol`):

```
./gradlew :app:assembleFullRelease   # or use Android Studio
```

For module-level compile checks during development: `./gradlew :ui:compileFullDebugKotlin`.
