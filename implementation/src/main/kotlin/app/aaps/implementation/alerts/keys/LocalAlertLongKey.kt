package app.aaps.implementation.alerts.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class LocalAlertLongKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    NextPumpDisconnectedAlarm("nextPumpDisconnectedAlarm", 0L),
    NextMissedReadingsAlarm("nextMissedReadingsAlarm", 0L),
    NextMissedLantusReminderAlarm("nextMissedLantusReminderAlarm", 0L)
}