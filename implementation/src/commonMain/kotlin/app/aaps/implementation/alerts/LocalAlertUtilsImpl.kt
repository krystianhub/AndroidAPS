package app.aaps.implementation.alerts

import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.BasalInjection
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.asAnnouncement
import app.aaps.core.ui.CoreUiStrings
import app.aaps.implementation.alerts.keys.LocalAlertLongKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Created by adrian on 17/12/17.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class LocalAlertUtilsImpl(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rh: TextResolver,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val smsCommunicator: SmsCommunicator,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val notificationManager: NotificationManager,
    private val appScope: CoroutineScope
) : LocalAlertUtils {

    init {
        preferences.registerPreferences(LocalAlertLongKey.entries)
    }

    private fun missedReadingsThreshold(): Long {
        return T.mins(preferences.get(IntKey.AlertsStaleDataThreshold).toLong()).msecs()
    }

    private fun pumpUnreachableThreshold(): Long {
        return T.mins(preferences.get(IntKey.AlertsPumpUnreachableThreshold).toLong()).msecs()
    }

    override fun checkPumpUnreachableAlarm(lastConnection: Long, isStatusOutdated: Boolean, isDisconnected: Boolean) {
        // MDI: there is no pump that could become unreachable - insulin is delivered by pen and
        // basal is a fixed once-daily Lantus injection. Skip the alarm and drop a stale one.
        if (activePlugin.activePump.isMDI()) {
            notificationManager.dismiss(NotificationId.PUMP_UNREACHABLE)
            return
        }
        val alarmTimeoutExpired = isAlarmTimeoutExpired(lastConnection, pumpUnreachableThreshold())
        val nextAlarmOccurrenceReached = preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm) < dateUtil.now()
        if (config.APS && isStatusOutdated && alarmTimeoutExpired && nextAlarmOccurrenceReached && !isDisconnected) {
            if (preferences.get(BooleanKey.AlertPumpUnreachable)) {
                aapsLogger.debug(LTag.CORE, "Generating pump unreachable alarm. lastConnection: " + dateUtil.dateAndTimeString(lastConnection) + " isStatusOutdated: true")
                preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, dateUtil.now() + pumpUnreachableThreshold())
                notificationManager.post(NotificationId.PUMP_UNREACHABLE, CoreUiStrings.pump_unreachable, sound = AlarmSound.ALARM)
                if (preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS)
                    appScope.launch {
                        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                            therapyEvent = TE.asAnnouncement(rh.gs(CoreUiStrings.pump_unreachable)),
                            timestamp = dateUtil.now(),
                            action = Action.CAREPORTAL,
                            source = Sources.Aaps,
                            note = rh.gs(CoreUiStrings.pump_unreachable),
                            listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                        )
                    }
            }
            if (preferences.get(BooleanKey.SmsReportPumpUnreachable))
                smsCommunicator.sendNotificationToAllNumbers(rh.gs(CoreUiStrings.pump_unreachable))
        }
        if (!isStatusOutdated && !alarmTimeoutExpired) notificationManager.dismiss(NotificationId.PUMP_UNREACHABLE)
    }

    private fun isAlarmTimeoutExpired(lastConnection: Long, unreachableThreshold: Long): Boolean {
        return if (activePlugin.activePump.pumpDescription.hasCustomUnreachableAlertCheck) {
            activePlugin.activePump.isUnreachableAlertTimeoutExceeded(unreachableThreshold)
        } else {
            lastConnection + pumpUnreachableThreshold() < dateUtil.now()
        }
    }

    /*Pre-snoozes the alarms with 5 minutes if no snooze exists.
     * Call only at startup!
     */
    override fun preSnoozeAlarms() {
        if (preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm) < dateUtil.now()) {
            preferences.put(LocalAlertLongKey.NextMissedReadingsAlarm, dateUtil.now() + 5 * 60 * 1000)
        }
        if (preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm) < dateUtil.now()) {
            preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, dateUtil.now() + 5 * 60 * 1000)
        }
    }

    override fun shortenSnoozeInterval() { //shortens alarm times in case of setting changes or future data
        var nextMissedReadingsAlarm = preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)
        nextMissedReadingsAlarm = min(dateUtil.now() + missedReadingsThreshold(), nextMissedReadingsAlarm)
        preferences.put(LocalAlertLongKey.NextMissedReadingsAlarm, nextMissedReadingsAlarm)
        var nextPumpDisconnectedAlarm = preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)
        nextPumpDisconnectedAlarm = min(dateUtil.now() + pumpUnreachableThreshold(), nextPumpDisconnectedAlarm)
        preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, nextPumpDisconnectedAlarm)
    }

    override suspend fun reportPumpStatusRead() {
        val pump = activePlugin.activePump
        val profile = profileFunction.getProfile()
        if (profile != null) {
            val lastConnection = pump.lastDataTime.value
            val earliestAlarmTime = lastConnection + pumpUnreachableThreshold()
            if (preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm) < earliestAlarmTime) {
                preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, earliestAlarmTime)
            }
        }
    }

    override suspend fun checkStaleBGAlert() {
        val bgReading = persistenceLayer.getLastGlucoseValue() ?: return
        if (preferences.get(BooleanKey.AlertMissedBgReading)
            && bgReading.timestamp + missedReadingsThreshold() < dateUtil.now()
            && preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm) < dateUtil.now()
        ) {
            preferences.put(LocalAlertLongKey.NextMissedReadingsAlarm, dateUtil.now() + missedReadingsThreshold())
            notificationManager.post(NotificationId.BG_READINGS_MISSED, CoreUiStrings.missed_bg_readings, sound = AlarmSound.ALARM)
            if (preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS) {
                appScope.launch {
                    persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                        therapyEvent = TE.asAnnouncement(rh.gs(CoreUiStrings.missed_bg_readings)),
                        timestamp = dateUtil.now(),
                        action = Action.CAREPORTAL,
                        source = Sources.Aaps,
                        note = rh.gs(CoreUiStrings.missed_bg_readings),
                        listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                    )
                }
            }
        } else if (dateUtil.isOlderThan(bgReading.timestamp, 5).not()) {
            notificationManager.dismiss(NotificationId.BG_READINGS_MISSED)
        }
    }

    /** MDI only: urgent reminder when no Lantus dose was recorded within 24 h + the configured grace period. */
    override suspend fun checkMissedLantusReminder() {
        val now = dateUtil.now()
        if (!activePlugin.activePump.isMDI() || !preferences.get(BooleanKey.MdiMissedLantusReminderEnabled)) {
            dismissMissedLantusReminder()
            return
        }
        val delay = lantusReminderDelay()
        val lastDose = lastLantusInjection(now)
        val nextReminder = preferences.get(LocalAlertLongKey.NextMissedLantusReminderAlarm)
        if (lastDose == null) {
            // Nothing in the window: a user who never records Lantus must not be nagged, but once a
            // cycle is running the dose only aged out - keep nagging.
            if (nextReminder == 0L) {
                dismissMissedLantusReminder()
                return
            }
        } else if (now - lastDose < delay) {
            // Log the armed schedule in the last hour of the wait. Without it "no reminder at the
            // configured hour" is indistinguishable from "the check never ran" (deferred worker).
            if (now - lastDose > delay - T.hours(1).msecs()) {
                aapsLogger.debug(
                    LTag.CORE,
                    "Missed Lantus reminder not due yet. lastDose: " + dateUtil.dateAndTimeString(lastDose) +
                        ", delay: " + durationString(delay) + ", due: " + dateUtil.dateAndTimeString(lastDose + delay)
                )
            }
            dismissMissedLantusReminder()
            return
        }
        if (nextReminder > now) {
            aapsLogger.debug(
                LTag.CORE,
                "Missed Lantus reminder throttled until " + dateUtil.dateAndTimeString(nextReminder) +
                    " (re-raise interval " + durationString(lantusReminderInterval()) + ")"
            )
            return
        }
        preferences.put(LocalAlertLongKey.NextMissedLantusReminderAlarm, now + lantusReminderInterval())
        // with no dose inside the window we only know "at least this long" - report the window itself
        val overdueMs = lastDose?.let { now - it } ?: lantusDoseLookback()
        val hoursOverdue = max((overdueMs / T.hours(1).msecs()).toInt(), 0)
        aapsLogger.debug(
            LTag.CORE,
            "Generating missed Lantus reminder. lastDose: " + (lastDose?.let { dateUtil.dateAndTimeString(it) } ?: "none within the lookback window") +
                " overdue: " + hoursOverdue + " h, delay: " + durationString(delay)
        )
        notificationManager.dismiss(NotificationId.MISSED_LANTUS_REMINDER)
        notificationManager.post(
            id = NotificationId.MISSED_LANTUS_REMINDER,
            text = rh.gs(CoreUiStrings.missed_lantus_reminder, hoursOverdue),
            validMinutes = 120,
            sound = AlarmSound.ALARM
        )
    }

    /** Latest NOTE therapy event recording a Lantus dose within the lookback window, or null. */
    private suspend fun lastLantusInjection(now: Long): Long? =
        try {
            persistenceLayer.getTherapyEventDataFromTime(now - lantusDoseLookback(), TE.Type.NOTE, false)
                .mapNotNull { te -> BasalInjection.extractDose(te.note)?.let { te.timestamp } }
                .maxOrNull()
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to read Lantus injections for the missed-Lantus reminder", e)
            null
        }

    /** Drops the reminder and resets its schedule so it can be raised fresh next time. */
    private fun dismissMissedLantusReminder() {
        if (preferences.get(LocalAlertLongKey.NextMissedLantusReminderAlarm) == 0L) return
        preferences.put(LocalAlertLongKey.NextMissedLantusReminderAlarm, 0L)
        notificationManager.dismiss(NotificationId.MISSED_LANTUS_REMINDER)
    }

    /** Delay after the last Lantus injection before reminders start: 24 h + configurable grace period. */
    private fun lantusReminderDelay(): Long =
        T.hours(24).msecs() + T.mins(preferences.get(IntKey.MdiMissedLantusGracePeriodMinutes).toLong()).msecs()

    /** Repeat the reminder at most once per configured interval until a dose is recorded. */
    private fun lantusReminderInterval(): Long =
        T.mins(preferences.get(IntKey.MdiMissedLantusReRaiseIntervalMinutes).toLong()).msecs()

    /** Lookback for the last Lantus injection, configurable (default 5 days). */
    private fun lantusDoseLookback(): Long =
        T.days(preferences.get(IntKey.MdiMissedLantusLookbackDays).toLong()).msecs()

    /** Compact "Xh Ym" for log output. */
    private fun durationString(ms: Long): String {
        val t = T.msecs(ms)
        return "${t.hours()}h ${t.mins() % 60}m"
    }
}