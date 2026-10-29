package app.aaps.implementation.alerts

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.alerts.keys.LocalAlertLongKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LocalAlertUtilsImplTest : TestBase() {

    @Mock lateinit var preferences: Preferences
    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var activePlugin: ActivePlugin
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var smsCommunicator: SmsCommunicator
    @Mock lateinit var config: Config
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var pump: Pump
    @Mock lateinit var pumpDescription: PumpDescription

    private lateinit var localAlertUtils: LocalAlertUtilsImpl

    private val now = 100000000L

    @BeforeEach
    fun setup() {
        localAlertUtils = LocalAlertUtilsImpl(
            aapsLogger,
            preferences,
            rxBus,
            rh,
            activePlugin,
            profileFunction,
            smsCommunicator,
            config,
            persistenceLayer,
            dateUtil
        )
        whenever(dateUtil.now()).thenReturn(now)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.pumpDescription).thenReturn(pumpDescription)
        whenever(pumpDescription.hasCustomUnreachableAlertCheck).thenReturn(false)
        whenever(persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(any(), any(), any(), any(), any(), any()))
            .thenReturn(Single.just(PersistenceLayer.TransactionResult()))
    }

    @Test
    fun `preSnoozeAlarms sets next missed readings alarm when expired`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now - 1000)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + 5 * 60 * 1000)
    }

    @Test
    fun `preSnoozeAlarms does not update next missed readings alarm when not expired`() {
        val futureTime = now + 10000
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(futureTime)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextMissedReadingsAlarm), any())
    }

    @Test
    fun `preSnoozeAlarms sets next pump disconnected alarm when expired`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 1000)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + 5 * 60 * 1000)
    }

    @Test
    fun `preSnoozeAlarms does not update pump disconnected alarm when not expired`() {
        val futureTime = now + 10000
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(futureTime)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(futureTime)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextPumpDisconnectedAlarm), any())
    }

    @Test
    fun `preSnoozeAlarms updates both alarms when both expired`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now - 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 2000)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + 5 * 60 * 1000)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + 5 * 60 * 1000)
    }

    @Test
    fun `shortenSnoozeInterval limits missed readings alarm to threshold`() {
        val thresholdMinutes = 30
        val farFutureAlarm = now + T.hours(5).msecs()

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(farFutureAlarm)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(30)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
    }

    @Test
    fun `shortenSnoozeInterval does not change alarm if already within threshold`() {
        val thresholdMinutes = 30
        val alarmTime = now + T.mins(10).msecs()

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(alarmTime)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(30)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(alarmTime)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, alarmTime)
    }

    @Test
    fun `shortenSnoozeInterval limits pump disconnected alarm to threshold`() {
        val thresholdMinutes = 20
        val farFutureAlarm = now + T.hours(10).msecs()

        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(30)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(farFutureAlarm)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
    }

    @Test
    fun `shortenSnoozeInterval handles both alarms correctly`() {
        val missedReadingsThreshold = 25
        val pumpUnreachableThreshold = 35

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(missedReadingsThreshold)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(pumpUnreachableThreshold)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + T.hours(1).msecs())
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now + T.hours(2).msecs())

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + T.mins(missedReadingsThreshold.toLong()).msecs())
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + T.mins(pumpUnreachableThreshold.toLong()).msecs())
    }

    @Test
    fun `reportPumpStatusRead updates alarm when profile is available`() {
        val lastDataTime = now - T.mins(5).msecs()
        val thresholdMinutes = 30
        val profile = org.mockito.kotlin.mock<app.aaps.core.interfaces.profile.Profile>()

        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(pump.lastDataTime).thenReturn(lastDataTime)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 1000)

        localAlertUtils.reportPumpStatusRead()

        val expectedAlarmTime = lastDataTime + T.mins(thresholdMinutes.toLong()).msecs()
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, expectedAlarmTime)
    }

    @Test
    fun `reportPumpStatusRead does not update alarm when profile is null`() {
        whenever(profileFunction.getProfile()).thenReturn(null)

        localAlertUtils.reportPumpStatusRead()

        verify(preferences, never()).put(any<LocalAlertLongKey>(), any<Long>())
    }

    @Test
    fun `reportPumpStatusRead does not decrease alarm time`() {
        val lastDataTime = now - T.mins(5).msecs()
        val thresholdMinutes = 30
        val futureAlarmTime = now + T.hours(2).msecs()
        val profile = org.mockito.kotlin.mock<app.aaps.core.interfaces.profile.Profile>()

        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(pump.lastDataTime).thenReturn(lastDataTime)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(futureAlarmTime)

        localAlertUtils.reportPumpStatusRead()

        // Should not update because futureAlarmTime is already later than earliestAlarmTime
        verify(preferences, never()).put(eq(LocalAlertLongKey.NextPumpDisconnectedAlarm), any())
    }

    @Test
    fun `preSnoozeAlarms uses exact 5 minute snooze`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now - 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 1000)

        localAlertUtils.preSnoozeAlarms()

        val expectedSnooze = now + 5 * 60 * 1000
        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, expectedSnooze)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, expectedSnooze)
    }

    @Test
    fun `shortenSnoozeInterval handles edge case with zero threshold`() {
        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(0)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(0)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now + 1000)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now)
    }

    @Test
    fun `shortenSnoozeInterval uses minimum of current alarm and threshold`() {
        val thresholdMinutes = 30
        val currentAlarmClose = now + T.mins(10).msecs()
        val currentAlarmFar = now + T.mins(50).msecs()

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)

        // Test with close alarm - should keep it
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(currentAlarmClose)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(currentAlarmClose)
        localAlertUtils.shortenSnoozeInterval()
        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, currentAlarmClose)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, currentAlarmClose)

        // Test with far alarm - should shorten it
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(currentAlarmFar)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(currentAlarmFar)
        localAlertUtils.shortenSnoozeInterval()
        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
    }

    @Test
    fun `reportPumpStatusRead calculates earliest alarm time correctly`() {
        val lastDataTime = now - T.mins(10).msecs()
        val thresholdMinutes = 40
        val currentAlarmTime = now + T.mins(5).msecs()
        val profile = org.mockito.kotlin.mock<app.aaps.core.interfaces.profile.Profile>()

        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(pump.lastDataTime).thenReturn(lastDataTime)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(currentAlarmTime)

        localAlertUtils.reportPumpStatusRead()

        val expectedEarliestAlarm = lastDataTime + T.mins(thresholdMinutes.toLong()).msecs()
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, expectedEarliestAlarm)
    }

    // region missed Lantus reminder

    /** Collects notification events sent on the bus while [block] runs. */
    private fun captureNotifications(block: () -> Unit): Pair<List<EventNewNotification>, List<EventDismissNotification>> {
        val newEvents = mutableListOf<EventNewNotification>()
        val dismissEvents = mutableListOf<EventDismissNotification>()
        val subs = listOf(
            rxBus.toObservable(EventNewNotification::class.java).subscribe { newEvents.add(it) },
            rxBus.toObservable(EventDismissNotification::class.java).subscribe { dismissEvents.add(it) }
        )
        block()
        subs.forEach { it.dispose() }
        return Pair(newEvents, dismissEvents)
    }

    private fun lantusNote(timestamp: Long, note: String? = "Lantus 20 U"): TE =
        TE(timestamp = timestamp, type = TE.Type.NOTE, note = note, glucoseUnit = GlucoseUnit.MGDL)

    private fun stubReminderEnabled(enabled: Boolean) {
        whenever(preferences.get(BooleanKey.MdiMissedLantusReminderEnabled)).thenReturn(enabled)
        if (enabled) stubReminderTimings()
    }

    private fun stubReminderTimings(
        graceMinutes: Int = 120,
        reRaiseIntervalMinutes: Int = 60,
        lookbackDays: Int = 5
    ) {
        whenever(preferences.get(IntKey.MdiMissedLantusGracePeriodMinutes)).thenReturn(graceMinutes)
        whenever(preferences.get(IntKey.MdiMissedLantusReRaiseIntervalMinutes)).thenReturn(reRaiseIntervalMinutes)
        whenever(preferences.get(IntKey.MdiMissedLantusLookbackDays)).thenReturn(lookbackDays)
    }

    private fun stubNextReminder(time: Long) =
        whenever(preferences.get(LocalAlertLongKey.NextMissedLantusReminderAlarm)).thenReturn(time)

    private fun stubTherapyEvents(events: List<TE>) =
        whenever(persistenceLayer.getTherapyEventDataFromTime(any(), eq(TE.Type.NOTE), eq(false))).thenReturn(events)

    @Test
    fun `checkMissedLantusReminder does nothing when no Lantus injection was recorded in the lookback window`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        stubNextReminder(0L)
        stubTherapyEvents(emptyList())

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(newEvents).isEmpty()
        assertThat(dismissEvents).isEmpty()
        verify(preferences, never()).put(eq(LocalAlertLongKey.NextMissedLantusReminderAlarm), any())
    }

    @Test
    fun `checkMissedLantusReminder looks back only the configured lookback window`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        stubNextReminder(0L)
        stubTherapyEvents(emptyList())

        localAlertUtils.checkMissedLantusReminder()

        verify(persistenceLayer).getTherapyEventDataFromTime(now - T.days(5).msecs(), TE.Type.NOTE, false)
    }

    @Test
    fun `checkMissedLantusReminder does not alert within 24 h + the grace period of the last dose`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        stubNextReminder(0L)
        stubTherapyEvents(listOf(lantusNote(now - T.hours(25).msecs())))

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(newEvents).isEmpty()
        assertThat(dismissEvents).isEmpty()
        verify(preferences, never()).put(eq(LocalAlertLongKey.NextMissedLantusReminderAlarm), any())
    }

    @Test
    fun `checkMissedLantusReminder alerts and schedules the next reminder when overdue`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        stubNextReminder(0L)
        stubTherapyEvents(listOf(lantusNote(now - T.hours(27).msecs())))
        whenever(rh.gs(eq(app.aaps.core.ui.R.string.missed_lantus_reminder), any())).thenReturn("Lantus injection overdue")
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("2024-01-01 12:00:00")

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(newEvents).hasSize(1)
        assertThat(newEvents.first().notification.id).isEqualTo(Notification.MISSED_LANTUS_REMINDER)
        assertThat(newEvents.first().notification.text).isEqualTo("Lantus injection overdue")
        assertThat(newEvents.first().notification.level).isEqualTo(Notification.URGENT)
        // Audible re-raise.
        assertThat(newEvents.first().notification.reRaiseOnDuplicate).isTrue()
        assertThat(newEvents.first().notification.soundId).isEqualTo(app.aaps.core.ui.R.raw.alarm)
        assertThat(dismissEvents).isEmpty()
        verify(preferences).put(LocalAlertLongKey.NextMissedLantusReminderAlarm, now + T.hours(1).msecs())
    }

    @Test
    fun `checkMissedLantusReminder does not re-alert within the configured interval window`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        stubNextReminder(now + T.mins(30).msecs())
        stubTherapyEvents(listOf(lantusNote(now - T.hours(27).msecs())))

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(newEvents).isEmpty()
        assertThat(dismissEvents).isEmpty()
        verify(preferences, never()).put(eq(LocalAlertLongKey.NextMissedLantusReminderAlarm), any())
    }

    @Test
    fun `checkMissedLantusReminder reminds again after the configured interval`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        val nextAlarm = now + T.hours(1).msecs()
        stubNextReminder(nextAlarm)
        stubTherapyEvents(listOf(lantusNote(now - T.hours(27).msecs())))
        whenever(rh.gs(eq(app.aaps.core.ui.R.string.missed_lantus_reminder), any())).thenReturn("Lantus injection overdue")
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("2024-01-01 12:00:00")
        whenever(dateUtil.now()).thenReturn(nextAlarm + T.mins(1).msecs())

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(newEvents).hasSize(1)
        assertThat(dismissEvents).isEmpty()
        verify(preferences).put(LocalAlertLongKey.NextMissedLantusReminderAlarm, nextAlarm + T.mins(1).msecs() + T.hours(1).msecs())
    }

    @Test
    fun `checkMissedLantusReminder dismisses a stale reminder when a dose was recorded recently`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        stubNextReminder(now + T.mins(30).msecs())
        stubTherapyEvents(listOf(lantusNote(now - T.hours(2).msecs())))

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(newEvents).isEmpty()
        assertThat(dismissEvents).hasSize(1)
        assertThat(dismissEvents.first().id).isEqualTo(Notification.MISSED_LANTUS_REMINDER)
        verify(preferences).put(LocalAlertLongKey.NextMissedLantusReminderAlarm, 0L)
    }

    @Test
    fun `checkMissedLantusReminder dismisses a stale reminder when disabled`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(false)
        stubNextReminder(now + T.mins(30).msecs())

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(newEvents).isEmpty()
        assertThat(dismissEvents).hasSize(1)
        verify(preferences).put(LocalAlertLongKey.NextMissedLantusReminderAlarm, 0L)
    }

    @Test
    fun `checkMissedLantusReminder dismisses a stale reminder on a pump`() {
        whenever(pump.isMDI()).thenReturn(false)
        stubNextReminder(now + T.mins(30).msecs())

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(newEvents).isEmpty()
        assertThat(dismissEvents).hasSize(1)
        verify(preferences).put(LocalAlertLongKey.NextMissedLantusReminderAlarm, 0L)
    }

    @Test
    fun `checkMissedLantusReminder keeps nagging when the last dose aged out of the lookback window`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        // an active reminder cycle: a dose was recorded before, it just fell out of the window
        stubNextReminder(now - T.mins(1).msecs())
        stubTherapyEvents(emptyList())
        whenever(rh.gs(eq(app.aaps.core.ui.R.string.missed_lantus_reminder), any())).thenReturn("Lantus injection overdue")
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("2024-01-01 12:00:00")

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(dismissEvents).isEmpty()
        assertThat(newEvents).hasSize(1)
        assertThat(newEvents.first().notification.id).isEqualTo(Notification.MISSED_LANTUS_REMINDER)
        // report the lookback window as the minimum overdue time
        verify(preferences).put(LocalAlertLongKey.NextMissedLantusReminderAlarm, now + T.hours(1).msecs())
    }

    @Test
    fun `checkMissedLantusReminder does not re-alert when the dose aged out but the interval is not due`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        stubNextReminder(now + T.mins(30).msecs())
        stubTherapyEvents(emptyList())

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(newEvents).isEmpty()
        assertThat(dismissEvents).isEmpty()
        verify(preferences, never()).put(eq(LocalAlertLongKey.NextMissedLantusReminderAlarm), any())
    }

    @Test
    fun `checkMissedLantusReminder ignores Lantus notes without a recorded dose`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        // an active cycle that is not due yet: a *parsed* recent dose would dismiss it, an
        // unparseable note must not - so "no dismiss / no put" proves the note was not a dose
        stubNextReminder(now + T.mins(30).msecs())
        stubTherapyEvents(listOf(lantusNote(now - T.hours(2).msecs(), note = "Lantus")))

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(newEvents).isEmpty()
        assertThat(dismissEvents).isEmpty()
        verify(preferences, never()).put(eq(LocalAlertLongKey.NextMissedLantusReminderAlarm), any())
    }

    @Test
    fun `checkMissedLantusReminder honours a custom grace period`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        stubReminderTimings(graceMinutes = 30)
        stubNextReminder(0L)
        stubTherapyEvents(listOf(lantusNote(now - T.hours(25).msecs())))
        whenever(rh.gs(eq(app.aaps.core.ui.R.string.missed_lantus_reminder), any())).thenReturn("Lantus injection overdue")
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("2024-01-01 12:00:00")

        val (newEvents, dismissEvents) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        // 25 h > 24 h + 30 min but < the 26 h default, so the custom grace period triggers the reminder.
        assertThat(newEvents).hasSize(1)
        assertThat(dismissEvents).isEmpty()
        verify(preferences).put(LocalAlertLongKey.NextMissedLantusReminderAlarm, now + T.mins(60).msecs())
    }

    @Test
    fun `checkMissedLantusReminder uses a custom re-raise interval`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        stubReminderTimings(reRaiseIntervalMinutes = 15)
        stubNextReminder(0L)
        stubTherapyEvents(listOf(lantusNote(now - T.hours(27).msecs())))
        whenever(rh.gs(eq(app.aaps.core.ui.R.string.missed_lantus_reminder), any())).thenReturn("Lantus injection overdue")
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("2024-01-01 12:00:00")

        val (newEvents, _) = captureNotifications { localAlertUtils.checkMissedLantusReminder() }

        assertThat(newEvents).hasSize(1)
        verify(preferences).put(LocalAlertLongKey.NextMissedLantusReminderAlarm, now + T.mins(15).msecs())
    }

    @Test
    fun `checkMissedLantusReminder honours a custom lookback window`() {
        whenever(pump.isMDI()).thenReturn(true)
        stubReminderEnabled(true)
        stubReminderTimings(lookbackDays = 10)
        stubNextReminder(0L)
        stubTherapyEvents(emptyList())

        localAlertUtils.checkMissedLantusReminder()

        verify(persistenceLayer).getTherapyEventDataFromTime(now - T.days(10).msecs(), TE.Type.NOTE, false)
    }

    // endregion
}
