package app.aaps.core.objects.wizard

import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.TextRef
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Commits a deferred carb entry at "Eat now". */
@SingleIn(AppScope::class)
@Inject
class PendingCarbCommitter(
    private val aapsLogger: AAPSLogger,
    private val rh: TextResolver,
    private val rxBus: RxBus,
    private val uel: UserEntryLogger,
    private val persistenceLayer: PersistenceLayer,
    private val uiInteraction: UiInteraction,
    private val dateUtil: DateUtil,
    private val pendingCarbStore: PendingCarbStore
) {

    /**
     * Commits the pending entry.
     *
     * @return false when nothing was committed - no entry, it expired, a commit of the same entry
     * is already running, or the write failed - so the caller can refresh the Overview instead of
     * leaving stale buttons on screen.
     */
    suspend fun commit(): Boolean {
        // the claim token makes a superseded callback a no-op
        val claim = pendingCarbStore.beginCommit() ?: return false
        val pending = claim.pending
        pending.mainCarbs.carbsTimestamp = dateUtil.now()
        return try {
            uel.log(
                action = Action.CARBS,
                source = pending.source,
                note = pending.mainCarbs.notes,
                listValues = listOf(ValueWithUnit.Gram(pending.mainCarbs.carbs.toInt()))
            )
            persistenceLayer.insertOrUpdateCarbs(
                carbs = pending.mainCarbs.createCarbs(),
                action = Action.CARBS,
                source = pending.source
            )
            if (!pendingCarbStore.commitSucceeded(claim.token)) return false
            rxBus.send(EventRefreshOverview("EatNow"))
            true
        } catch (e: Exception) {
            aapsLogger.error(LTag.DATABASE, "Failed to persist deferred carbs", e)
            pendingCarbStore.commitFailed(claim.token)
            uiInteraction.runAlarm(
                e.message ?: e.toString(),
                rh.gs(TextRef.Literal("Treatment delivery error")),
                AlarmSound.BOLUS_ERROR
            )
            false
        }
    }
}
