package app.aaps.core.objects.wizard

import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds a deferred carb entry that was logged by the Bolus Wizard but not yet committed.
 *
 * The entry is persisted in SharedPreferences so it survives process death / reboot. To keep the
 * frequent Overview refresh path cheap, the parsed entry is cached in memory after the first load
 * and only written back to SharedPreferences on mutation. An entry older than [TIMEOUT_MS] is
 * treated as obsolete and ignored.
 */
@Singleton
class PendingCarbStore @Inject constructor(
    private val dateUtil: DateUtil,
    private val sp: SP
) {

    // In-memory cache. Loaded lazily from SP on first access so the UI refresh path does not
    // re-parse JSON on every call. The SP copy remains the source of truth across restarts.
    private var cached: PendingCarbs? = null
    private var loaded = false
    // Set when an entry is found past its timeout. Kept until [consumeExpired] so the UI can
    // inform the user exactly once (also across a process restart, until the SP key is removed).
    private var expired: PendingCarbs? = null
    // Set while a commit is in flight so two "Eat now" taps cannot insert the same carbs twice.
    private var committing = false

    @Synchronized
    fun setPending(carbs: PendingCarbs) {
        val stamped = carbs.copy(createdAt = dateUtil.now())
        cached = stamped
        loaded = true
        expired = null
        committing = false
        sp.putString(KEY, stamped.toJson().toString())
    }

    @Synchronized
    fun clearPending() {
        cached = null
        loaded = true
        expired = null
        committing = false
        sp.remove(KEY)
    }

    /** Returns true if a deferred carb entry exists and has not exceeded its timeout. */
    @Synchronized
    fun isPending(): Boolean = pendingOrNull() != null

    @Synchronized
    fun getPending(): PendingCarbs? = pendingOrNull()

    /**
     * Atomically claims the pending entry for a commit. Returns null when nothing is pending or a
     * commit is already in progress. The entry stays in SP until [commitSucceeded], so a failed
     * write or a process death cannot lose it.
     */
    @Synchronized
    fun beginCommit(): PendingCarbs? {
        if (committing) return null
        val pending = pendingOrNull() ?: return null
        committing = true
        return pending
    }

    /** Drops the claimed entry after a successful commit. */
    @Synchronized
    fun commitSucceeded() {
        clearPending()
    }

    /** Releases the claim after a failed commit so the user can retry "Eat now". */
    @Synchronized
    fun commitFailed() {
        committing = false
    }

    /**
     * Returns and clears the entry that expired, so the caller can inform the user once. The
     * persisted copy is removed here rather than on detection, so the expiry can still be reported
     * after a process restart.
     */
    @Synchronized
    fun consumeExpired(): PendingCarbs? {
        val e = expired ?: return null
        expired = null
        sp.remove(KEY)
        return e
    }

    /**
     * Serves the cached entry, loading it from SP once. An expired entry is dropped from memory and
     * recorded in [expired]; the persisted copy is only removed by [consumeExpired] so the expiry
     * can still be reported after a process restart.
     */
    private fun pendingOrNull(): PendingCarbs? {
        if (!loaded) {
            cached = load()
            loaded = true
        }
        val pending = cached ?: return null
        if (dateUtil.now() - pending.createdAt > TIMEOUT_MS) {
            cached = null
            expired = pending
            return null
        }
        return pending
    }

    private fun load(): PendingCarbs? {
        val json = sp.getStringOrNull(KEY, null) ?: return null
        return try {
            JSONObject(json).toPendingCarbs()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val KEY = "pending_carbs_entry"
        private const val TIMEOUT_MS = 60 * 60 * 1000L // 1 hour
    }
}

data class PendingCarbs(
    val mainCarbs: DetailedBolusInfo,
    val mealPlan: MealMacroPlan? = null,
    val quickWizardECarbs: QuickWizardECarbs? = null,
    val source: Sources = Sources.WizardDialog,
    /** Stamped by [PendingCarbStore.setPending]; a value passed here is ignored. */
    val createdAt: Long = 0L
)

data class QuickWizardECarbs(
    val carbs2: Int,
    val timeOffsetMin: Int,
    val durationH: Int,
    val notes: String
)

private fun PendingCarbs.toJson(): JSONObject = JSONObject().apply {
    put("carbs", mainCarbs.carbs)
    put("carbsTimestamp", mainCarbs.carbsTimestamp)
    put("carbsDuration", mainCarbs.carbsDuration)
    put("notes", mainCarbs.notes ?: JSONObject.NULL)
    put("eventType", mainCarbs.eventType.name)
    put("mgdlGlucose", mainCarbs.mgdlGlucose ?: JSONObject.NULL)
    put("glucoseType", mainCarbs.glucoseType?.name ?: JSONObject.NULL)
    put("source", source.name)
    put("createdAt", createdAt)
    mealPlan?.let { plan ->
        put("plan", JSONObject().apply {
            put("upfrontCarbs", plan.upfrontCarbs)
            put("primaryTailCarbs", plan.primaryTailCarbs)
            put("fatTailCarbs", plan.fatTailCarbs)
            put("suggestedUpfrontPercentage", plan.suggestedUpfrontPercentage)
            put("primaryTailShiftMin", plan.primaryTailShiftMin)
            put("primaryTailDurationH", plan.primaryTailDurationH)
            put("fatTailShiftMin", plan.fatTailShiftMin)
            put("fatTailDurationH", plan.fatTailDurationH)
            put("intensity", plan.intensity)
            put("fat", plan.fat)
            put("protein", plan.protein)
        })
    }
    quickWizardECarbs?.let { e ->
        put("qw", JSONObject().apply {
            put("carbs2", e.carbs2)
            put("timeOffsetMin", e.timeOffsetMin)
            put("durationH", e.durationH)
            put("notes", e.notes)
        })
    }
}

private fun JSONObject.toPendingCarbs(): PendingCarbs? {
    val carbs = optDouble("carbs", 0.0)
    if (carbs <= 0.0) return null
    val mainCarbs = DetailedBolusInfo().apply {
        this.carbs = carbs
        carbsTimestamp = optLong("carbsTimestamp", 0L)
        carbsDuration = optLong("carbsDuration", 0L)
        notes = if (isNull("notes")) null else optString("notes")
        eventType = TE.Type.valueOf(optString("eventType", TE.Type.BOLUS_WIZARD.name))
        mgdlGlucose = if (isNull("mgdlGlucose")) null else optDouble("mgdlGlucose")
        glucoseType = if (isNull("glucoseType")) null else TE.MeterType.valueOf(optString("glucoseType"))
    }
    val mealPlan = optJSONObject("plan")?.let { plan ->
        MealMacroPlan(
            upfrontCarbs = plan.optInt("upfrontCarbs"),
            primaryTailCarbs = plan.optInt("primaryTailCarbs"),
            fatTailCarbs = plan.optInt("fatTailCarbs"),
            suggestedUpfrontPercentage = plan.optInt("suggestedUpfrontPercentage"),
            primaryTailShiftMin = plan.optInt("primaryTailShiftMin"),
            primaryTailDurationH = plan.optInt("primaryTailDurationH"),
            fatTailShiftMin = plan.optInt("fatTailShiftMin"),
            fatTailDurationH = plan.optInt("fatTailDurationH"),
            intensity = plan.optDouble("intensity", 0.0),
            fat = plan.optInt("fat"),
            protein = plan.optInt("protein")
        )
    }
    val quickWizardECarbs = optJSONObject("qw")?.let { e ->
        QuickWizardECarbs(
            carbs2 = e.optInt("carbs2"),
            timeOffsetMin = e.optInt("timeOffsetMin"),
            durationH = e.optInt("durationH"),
            notes = e.optString("notes", "")
        )
    }
    return PendingCarbs(
        mainCarbs = mainCarbs,
        mealPlan = mealPlan,
        quickWizardECarbs = quickWizardECarbs,
        source = Sources.valueOf(optString("source", Sources.WizardDialog.name)),
        createdAt = optLong("createdAt", 0L)
    )
}
