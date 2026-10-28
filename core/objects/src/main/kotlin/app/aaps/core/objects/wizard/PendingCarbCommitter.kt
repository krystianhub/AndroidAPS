package app.aaps.core.objects.wizard

import android.content.Context
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Commits a deferred carb entry at "Eat now": logs the main carbs and schedules the extended carb
 * tails of a meal macro plan relative to the current time.
 */
@Singleton
class PendingCarbCommitter @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val rxBus: RxBus,
    private val uel: UserEntryLogger,
    private val persistenceLayer: PersistenceLayer,
    private val eCarbsScheduler: ECarbsScheduler,
    private val uiInteraction: UiInteraction,
    private val dateUtil: DateUtil,
    private val pendingCarbStore: PendingCarbStore
) {

    /**
     * Commits the pending entry.
     *
     * @return false when nothing was committed - no entry, it expired, a commit of the same entry
     * is already running, or the write could not be started - so the caller can refresh the
     * Overview instead of leaving stale buttons on screen.
     */
    fun commit(ctx: Context): Boolean {
        val pending = pendingCarbStore.beginCommit() ?: return false
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
            ).subscribe({
                // Drop the claim as soon as the main carbs are persisted: the tails are best effort
                // and must not leave the store stuck in "committing", which would block "Eat now".
                pendingCarbStore.commitSucceeded()
                pending.mealPlan?.let { plan ->
                    try {
                        eCarbsScheduler.schedule(ctx, plan, pending.source, baseNotes = pending.mainCarbs.notes ?: "", mealOffsetMin = 0)
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.DATABASE, "Failed to schedule deferred eCarbs", e)
                    }
                }
                rxBus.send(EventRefreshOverview("EatNow"))
            }, {
                aapsLogger.error(LTag.DATABASE, "Failed to persist deferred carbs", it)
                pendingCarbStore.commitFailed()
                uiInteraction.runAlarm(it.message ?: it.toString(), rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
            })
            true
        } catch (e: Exception) {
            // Nothing reached the persistence layer, so release the claim and let the user retry
            aapsLogger.error(LTag.DATABASE, "Failed to start deferred carb commit", e)
            pendingCarbStore.commitFailed()
            false
        }
    }
}
