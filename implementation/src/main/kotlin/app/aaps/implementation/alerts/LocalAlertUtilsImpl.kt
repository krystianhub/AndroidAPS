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
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.BasalInjection
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.asAnnouncement
import app.aaps.core.ui.R
import app.aaps.implementation.alerts.keys.LocalAlertLongKey
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Created by adrian on 17/12/17.
 */
@Singleton
class LocalAlertUtilsImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val rh: ResourceHelper,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val smsCommunicator: SmsCommunicator,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil
) : LocalAlertUtils {

    init {
        preferences.registerPreferences(LocalAlertLongKey::class.java)
    }

    private val disposable = CompositeDisposable()

    private fun missedReadingsThreshold(): Long {
        return T.mins(preferences.get(IntKey.AlertsStaleDataThreshold).toLong()).msecs()
    }

    private fun pumpUnreachableThreshold(): Long {
        return T.mins(preferences.get(IntKey.AlertsPumpUnreachableThreshold).toLong()).msecs()
    }

    override fun checkPumpUnreachableAlarm(lastConnection: Long, isStatusOutdated: Boolean, isDisconnected: Boolean) {
        // MDI: there is no pump that could become unreachable - insulin is delivered by pen and
        // basal is a fixed once-daily Lantus injection. Skip the alarm and drop a stale one.
        // Note the preference is hidden in MDI settings, so the user cannot re-enable it.
        if (activePlugin.activePump.isMDI()) {
            rxBus.send(EventDismissNotification(Notification.PUMP_UNREACHABLE))
            return
        }
        val alarmTimeoutExpired = isAlarmTimeoutExpired(lastConnection, pumpUnreachableThreshold())
        val nextAlarmOccurrenceReached = preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm) < dateUtil.now()
        if (config.APS && isStatusOutdated && alarmTimeoutExpired && nextAlarmOccurrenceReached && !isDisconnected) {
            if (preferences.get(BooleanKey.AlertPumpUnreachable)) {
                aapsLogger.debug(LTag.CORE, "Generating pump unreachable alarm. lastConnection: " + dateUtil.dateAndTimeString(lastConnection) + " isStatusOutdated: true")
                preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, dateUtil.now() + pumpUnreachableThreshold())
                rxBus.send(EventNewNotification(Notification(Notification.PUMP_UNREACHABLE, rh.gs(R.string.pump_unreachable), Notification.URGENT).also { it.soundId = R.raw.alarm }))
                if (preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS)
                    disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                        therapyEvent = TE.asAnnouncement(rh.gs(R.string.pump_unreachable)),
                        timestamp = dateUtil.now(),
                        action = Action.CAREPORTAL,
                        source = Sources.Aaps,
                        note = rh.gs(R.string.pump_unreachable),
                        listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                    ).subscribe()
            }
            if (preferences.get(BooleanKey.SmsReportPumpUnreachable))
                smsCommunicator.sendNotificationToAllNumbers(rh.gs(R.string.pump_unreachable))
        }
        if (!isStatusOutdated && !alarmTimeoutExpired) rxBus.send(EventDismissNotification(Notification.PUMP_UNREACHABLE))
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

    override fun reportPumpStatusRead() {
        val pump = activePlugin.activePump
        val profile = profileFunction.getProfile()
        if (profile != null) {
            val lastConnection = pump.lastDataTime
            val earliestAlarmTime = lastConnection + pumpUnreachableThreshold()
            if (preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm) < earliestAlarmTime) {
                preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, earliestAlarmTime)
            }
        }
    }

    override fun checkStaleBGAlert() {
        val bgReading = persistenceLayer.getLastGlucoseValue() ?: return
        if (preferences.get(BooleanKey.AlertMissedBgReading)
            && bgReading.timestamp + missedReadingsThreshold() < dateUtil.now()
            && preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm) < dateUtil.now()
        ) {
            val n = Notification(Notification.BG_READINGS_MISSED, rh.gs(R.string.missed_bg_readings), Notification.URGENT)
            n.soundId = R.raw.alarm
            preferences.put(LocalAlertLongKey.NextMissedReadingsAlarm, dateUtil.now() + missedReadingsThreshold())
            rxBus.send(EventNewNotification(n))
            if (preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS) {
                disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                    therapyEvent = TE.asAnnouncement(n.text),
                    timestamp = dateUtil.now(),
                    action = Action.CAREPORTAL,
                    source = Sources.Aaps,
                    note = rh.gs(R.string.missed_bg_readings),
                    listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                ).subscribe()
            }
        } else if (dateUtil.isOlderThan(bgReading.timestamp, 5).not()) {
            rxBus.send(EventDismissNotification(Notification.BG_READINGS_MISSED))
        }
    }

    /** MDI only: urgent reminder when no Lantus dose was recorded within 24 h + the configured grace period. */
    override fun checkMissedLantusReminder() {
        val now = dateUtil.now()
        if (!activePlugin.activePump.isMDI() || !preferences.get(BooleanKey.MdiMissedLantusReminderEnabled)) {
            dismissMissedLantusReminder()
            return
        }
        val lastDose = lastLantusInjection(now)
        val nextReminder = preferences.get(LocalAlertLongKey.NextMissedLantusReminderAlarm)
        if (lastDose == null) {
            // Nothing in the window: a user who never records Lantus must not be nagged, but once a
            // cycle is running the dose only aged out - keep nagging.
            if (nextReminder == 0L) {
                dismissMissedLantusReminder()
                return
            }
        } else if (now - lastDose < lantusReminderDelay()) {
            dismissMissedLantusReminder()
            return
        }
        if (nextReminder > now) return
        preferences.put(LocalAlertLongKey.NextMissedLantusReminderAlarm, now + lantusReminderInterval())
        // with no dose inside the window we only know "at least this long" - report the window itself
        val overdueMs = lastDose?.let { now - it } ?: lantusDoseLookback()
        val hoursOverdue = max((overdueMs / T.hours(1).msecs()).toInt(), 0)
        aapsLogger.debug(
            LTag.CORE,
            "Generating missed Lantus reminder. lastDose: " + (lastDose?.let { dateUtil.dateAndTimeString(it) } ?: "none within the lookback window") + " overdue: " + hoursOverdue + " h"
        )
        val notification = Notification(
            Notification.MISSED_LANTUS_REMINDER,
            rh.gs(R.string.missed_lantus_reminder, hoursOverdue),
            Notification.URGENT,
            validMinutes = 120
        ).apply {
            // Re-alert audibly on each interval.
            reRaiseOnDuplicate = true
            soundId = R.raw.alarm
        }
        rxBus.send(EventNewNotification(notification))
    }

    /** Latest NOTE therapy event recording a Lantus dose within the lookback window, or null. */
    private fun lastLantusInjection(now: Long): Long? =
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
        rxBus.send(EventDismissNotification(Notification.MISSED_LANTUS_REMINDER))
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
}
