package app.aaps.plugins.sync.healthconnect.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

enum class HealthConnectStringNonKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    /** Health Connect changes token for incremental reads (empty = full read needed). */
    ChangesToken("healthconnect_changes_token", ""),
}
