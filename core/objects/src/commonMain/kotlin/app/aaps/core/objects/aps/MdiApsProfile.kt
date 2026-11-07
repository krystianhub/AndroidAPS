package app.aaps.core.objects.aps

/**
 * Internal APS profile values used when the pump is MDI (pen user).
 *
 * The pump-only caps (Max basal, safety multipliers, SMB basal-minutes limits) are hidden in MDI
 * preferences because they cannot limit pen bolus suggestions - but they still shape what the APS
 * algorithm requests (maxSafeBasal caps the temp-basal rate, maxSMBBasalMinutes caps the SMB size).
 * With hidden defaults (1 U/h max basal, 30 SMB minutes) requests would be capped so low that no
 * actionable pen suggestion could ever be produced. Therefore in MDI mode these values are
 * overridden at OapsProfile construction with "unbound" values, so the effective limits become:
 *  - per-suggestion size: [app.aaps.core.keys.DoubleKey.MdiMaxBolusSuggestion] (LoopPlugin)
 *  - cumulative size: Max IOB preference (unchanged)
 */
object MdiApsProfile {

    /** Safety multipliers set high enough that they never bind below the max-basal hard limit */
    const val UNBOUND_MULTIPLIER = 1000.0

    /** SMB basal-minutes caps set high enough that they never bind a single suggestion */
    const val MAX_SMB_MINUTES = 720
}
