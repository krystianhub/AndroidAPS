package app.aaps.core.objects.wizard

import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewNotification
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
    private val sp: SP,
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val rxBus: RxBus
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
    // A claim older than [CLAIM_TIMEOUT_MS] can be taken over, so a lost Rx callback cannot block
    // "Eat now" forever. The token makes the superseded claim's late callback a no-op.
    private var commitStartedAt = 0L
    private var claimToken = 0L

    @Synchronized
    fun setPending(carbs: PendingCarbs) {
        val stamped = carbs.copy(createdAt = dateUtil.now())
        cached = stamped
        loaded = true
        expired = null
        committing = false
        commitStartedAt = 0L
        claimToken++
        writePending(stamped.toJson().toString())
    }

    @Synchronized
    fun clearPending() {
        cached = null
        loaded = true
        expired = null
        committing = false
        commitStartedAt = 0L
        claimToken++
        removePending()
    }

    /** Returns true if a deferred carb entry exists and has not exceeded its timeout. */
    @Synchronized
    fun isPending(): Boolean = pendingOrNull() != null

    @Synchronized
    fun getPending(): PendingCarbs? = pendingOrNull()

    /**
     * Atomically claims the pending entry for a commit, null when nothing is pending or a commit is
     * already running.
     *
     * The persisted copy is removed when the claim is taken (at-least-once): a process death after
     * the DB write cannot insert the same carbs twice - dying in the short window between claim and
     * write loses them instead, which is the safer failure. Pass the [PendingCarbClaim.token] back
     * to [commitSucceeded]/[commitFailed]; a superseded claim is ignored.
     */
    @Synchronized
    fun beginCommit(): PendingCarbClaim? {
        if (committing && !claimExpired()) return null
        val pending = pendingOrNull() ?: return null
        committing = true
        commitStartedAt = dateUtil.now()
        cached = pending
        claimToken++
        removePending()
        return PendingCarbClaim(pending, claimToken)
    }

    /** Drops the claimed entry; false when the claim was superseded - the caller must then skip the tails. */
    @Synchronized
    fun commitSucceeded(token: Long): Boolean {
        if (token != claimToken) return false
        clearPending()
        return true
    }

    /** Releases the claim after a failed commit and restores the persisted copy for a retry. */
    @Synchronized
    fun commitFailed(token: Long) {
        if (token != claimToken) return
        committing = false
        commitStartedAt = 0L
        cached?.let { writePending(it.toJson().toString()) }
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
        // a stale expiry marker must not wipe an entry set in the meantime
        if (cached == null) removePending()
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

    private fun claimExpired(): Boolean = dateUtil.now() - commitStartedAt > CLAIM_TIMEOUT_MS

    /**
     * `commit = true`: the stored entry is the record of an already-injected bolus, so it must not
     * sit in the async apply() queue - a process kill would resurrect a consumed entry.
     */
    private fun writePending(json: String) {
        sp.edit(commit = true) { putString(KEY, json) }
    }

    private fun removePending() {
        sp.edit(commit = true) { remove(KEY) }
    }

    private fun load(): PendingCarbs? {
        val json = sp.getStringOrNull(KEY, null) ?: return null
        return try {
            JSONObject(json).toPendingCarbs() ?: run {
                reportUnreadable(null)
                null
            }
        } catch (e: Exception) {
            reportUnreadable(e)
            null
        }
    }

    /** A silently dropped entry leaves the logged bolus without carbs - report it. */
    private fun reportUnreadable(cause: Throwable?) {
        if (cause == null) aapsLogger.error(LTag.DATABASE, "Discarding unreadable deferred carb entry")
        else aapsLogger.error(LTag.DATABASE, "Discarding unreadable deferred carb entry", cause)
        removePending()
        rxBus.send(
            EventNewNotification(
                Notification(Notification.USER_MESSAGE, rh.gs(app.aaps.core.ui.R.string.deferred_carbs_unreadable), Notification.NORMAL)
            )
        )
    }

    companion object {
        private const val KEY = "pending_carbs_entry"
        private const val TIMEOUT_MS = 60 * 60 * 1000L // 1 hour
        private const val CLAIM_TIMEOUT_MS = 60 * 1000L // 1 minute
    }
}

data class PendingCarbs(
    val mainCarbs: DetailedBolusInfo,
    val mealPlan: MealMacroPlan? = null,
    val source: Sources = Sources.WizardDialog,
    /** Stamped by [PendingCarbStore.setPending]; a value passed here is ignored. */
    val createdAt: Long = 0L
)

/** A claimed deferred entry; [token] identifies the claim for [PendingCarbStore.commitSucceeded]/`commitFailed`. */
data class PendingCarbClaim(val pending: PendingCarbs, val token: Long)

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
}

private fun JSONObject.toPendingCarbs(): PendingCarbs? {
    val carbs = optDouble("carbs", 0.0)
    val planJson = optJSONObject("plan")
    // a tails-only plan is valid with 0 main carbs
    if (carbs <= 0.0 && planJson == null) return null
    val mainCarbs = DetailedBolusInfo().apply {
        this.carbs = carbs
        carbsTimestamp = optLong("carbsTimestamp", 0L)
        carbsDuration = optLong("carbsDuration", 0L)
        notes = if (isNull("notes")) null else optString("notes")
        eventType = TE.Type.valueOf(optString("eventType", TE.Type.BOLUS_WIZARD.name))
        mgdlGlucose = if (isNull("mgdlGlucose")) null else optDouble("mgdlGlucose")
        glucoseType = if (isNull("glucoseType")) null else TE.MeterType.valueOf(optString("glucoseType"))
    }
    val mealPlan = planJson?.let { plan ->
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
    return PendingCarbs(
        mainCarbs = mainCarbs,
        mealPlan = mealPlan,
        source = Sources.valueOf(optString("source", Sources.WizardDialog.name)),
        createdAt = optLong("createdAt", 0L)
    )
}
