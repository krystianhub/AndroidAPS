package app.aaps.core.objects.wizard

import android.content.Context
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules extended carbs records (a carb amount spread over a duration, optionally starting in
 * the future). In MDI mode the record is persisted directly - there is no pump to deliver with.
 *
 * Shared by the Bolus Wizard (at confirmation) and the deferred carb commit (at "Eat now").
 */
@Singleton
class ECarbsScheduler @Inject constructor(
    private val rh: ResourceHelper,
    private val activePlugin: ActivePlugin,
    private val commandQueue: CommandQueue,
    private val persistenceLayer: PersistenceLayer,
    private val uel: UserEntryLogger,
    private val uiInteraction: UiInteraction,
    private val dateUtil: DateUtil
) {

    /** Schedules both tails of a meal macro plan, relative to a meal starting [mealOffsetMin] from now. */
    fun schedule(ctx: Context, plan: MealMacroPlan, source: Sources, baseNotes: String, mealOffsetMin: Int) {
        // tail shifts are relative to the meal start (carb time), not to "now" - e.g. with
        // "eat in 10 min" the +60 min protein tail must start at now + 70 min
        if (plan.primaryTailCarbs > 0)
            schedule(ctx, plan.primaryTailCarbs, plan.primaryTailShiftMin + mealOffsetMin, plan.primaryTailDurationH, tailNotes(baseNotes, "P"), source)
        if (plan.fatTailCarbs > 0)
            schedule(ctx, plan.fatTailCarbs, plan.fatTailShiftMin + mealOffsetMin, plan.fatTailDurationH, tailNotes(baseNotes, "F"), source)
    }

    /** Schedules one extended carbs record (carbs spread over duration, starting after timeOffset). */
    fun schedule(ctx: Context, carbs: Int, timeOffsetMin: Int, durationH: Int, notes: String, source: Sources) {
        val currentTime = dateUtil.now()
        val eventTime: Long = currentTime + (timeOffsetMin * 60000L)
        val duration = durationH.coerceAtLeast(0)

        val detailedBolusInfo = DetailedBolusInfo()
        detailedBolusInfo.eventType = TE.Type.CORRECTION_BOLUS
        detailedBolusInfo.carbs = carbs.toDouble()
        detailedBolusInfo.context = ctx
        detailedBolusInfo.notes = notes
        detailedBolusInfo.carbsDuration = T.hours(duration.toLong()).msecs()
        detailedBolusInfo.carbsTimestamp = eventTime
        uel.log(
            action = Action.EXTENDED_CARBS,
            source = source,
            note = notes,
            listValues = listOfNotNull(
                ValueWithUnit.Timestamp(eventTime),
                ValueWithUnit.Gram(carbs),
                ValueWithUnit.Minute(timeOffsetMin).takeIf { timeOffsetMin != 0 },
                ValueWithUnit.Hour(duration).takeIf { duration != 0 }
            )
        )
        if (activePlugin.activePump.isMDI()) {
            // MDI: no pump to deliver with - record the carbs directly
            persistenceLayer.insertOrUpdateCarbs(
                carbs = detailedBolusInfo.createCarbs(),
                action = Action.EXTENDED_CARBS,
                source = source
            ).subscribe()
        } else {
            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                override fun run() {
                    if (!result.success) {
                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                    }
                }
            })
        }
    }

    /** Notes for a macro tail record: the wizard notes plus a marker so the tails can be told apart later */
    private fun tailNotes(base: String, tag: String): String =
        if (base.isBlank()) "macro-$tag" else "$base macro-$tag"
}
