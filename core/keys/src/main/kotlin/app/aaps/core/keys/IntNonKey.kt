package app.aaps.core.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class IntNonKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true
) : IntNonPreferenceKey {

    ObjectivesManualEnacts("ObjectivesmanualEnacts", 0),
    RangeToDisplay("rangetodisplay", 6),

    /** Heart rate smoothing window in minutes (1 = off, max 15), mirroring the Wear sender's key_heart_rate_smoothing. */
    HeartRateSmoothing("healthconnect_hr_smoothing", 1)
}