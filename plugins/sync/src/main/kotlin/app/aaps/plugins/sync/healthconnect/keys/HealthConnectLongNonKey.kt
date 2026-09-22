package app.aaps.plugins.sync.healthconnect.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class HealthConnectLongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    /** Timestamp of the newest Health Connect record already imported. */
    LastRead("healthconnect_last_read", 0L),
}
