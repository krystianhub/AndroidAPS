package app.aaps.core.objects.wizard

import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Holds a deferred carb entry that was logged by the Bolus Wizard but not yet committed.
 *
 * The entry is persisted in the preference store so it survives process death / reboot. To keep the
 * frequent Overview refresh path cheap, the parsed entry is cached in memory after the first load
 * and only written back on mutation. An entry older than [TIMEOUT_MS] is treated as obsolete.
 */
@SingleIn(AppScope::class)
@Inject
class PendingCarbStore(
    private val dateUtil: DateUtil,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger,
    private val rh: TextResolver,
    private val notificationManager: NotificationManager
) {

    private val json = Json { ignoreUnknownKeys = true }

    // In-memory cache. Loaded lazily from the store on first access so the UI refresh path does not
    // re-parse JSON on every call. The persisted copy remains the source of truth across restarts.
    private var cached: PendingCarbs? = null
    private var loaded = false
    // Set when an entry is found past its timeout. Kept until [consumeExpired] so the UI can
    // inform the user exactly once (also across a process restart, until the key is removed).
    private var expired: PendingCarbs? = null
    // Set while a commit is in flight so two "Eat now" taps cannot insert the same carbs twice.
    private var committing = false
    // A claim older than [CLAIM_TIMEOUT_MS] can be taken over, so a lost callback cannot block
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
        writePending(stamped.toJson())
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

    /** Drops the claimed entry; false when the claim was superseded. */
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
        cached?.let { writePending(it.toJson()) }
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
     * Serves the cached entry, loading it from the store once. An expired entry is dropped from
     * memory and recorded in [expired]; the persisted copy is only removed by [consumeExpired] so
     * the expiry can still be reported after a process restart.
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
     * Synchronous on purpose: the stored entry records an already-injected bolus, so a process kill
     * must not resurrect a consumed entry from an asynchronous write queue.
     */
    private fun writePending(json: String) {
        preferences.putSync(StringNonKey.PendingCarbs, json)
    }

    private fun removePending() {
        preferences.putSync(StringNonKey.PendingCarbs, "")
    }

    private fun load(): PendingCarbs? {
        val stored = preferences.get(StringNonKey.PendingCarbs)
        if (stored.isEmpty()) return null
        return try {
            stored.toPendingCarbs() ?: run {
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
        notificationManager.post(
            NotificationId.DEFERRED_CARBS_UNREADABLE,
            rh.gs(InterfacesStrings.deferred_carbs_unreadable)
        )
    }

    private fun PendingCarbs.toJson(): String = buildJsonObject {
        put("carbs", mainCarbs.carbs)
        put("carbsTimestamp", mainCarbs.carbsTimestamp)
        put("carbsDuration", mainCarbs.carbsDuration)
        mainCarbs.notes?.let { put("notes", it) }
        put("eventType", mainCarbs.eventType.name)
        mainCarbs.mgdlGlucose?.let { put("mgdlGlucose", it) }
        mainCarbs.glucoseType?.let { put("glucoseType", it.name) }
        put("source", source.name)
        put("createdAt", createdAt)
    }.toString()

    private fun String.toPendingCarbs(): PendingCarbs? {
        val obj = json.parseToJsonElement(this).jsonObject
        val carbs = obj["carbs"]?.jsonPrimitive?.doubleOrNull ?: return null
        if (carbs <= 0.0) return null
        val mainCarbs = DetailedBolusInfo().apply {
            this.carbs = carbs
            carbsTimestamp = obj["carbsTimestamp"]?.jsonPrimitive?.longOrNull ?: 0L
            carbsDuration = obj["carbsDuration"]?.jsonPrimitive?.longOrNull ?: 0L
            notes = obj["notes"]?.jsonPrimitive?.contentOrNull
            eventType = obj["eventType"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { TE.Type.valueOf(it) }.getOrNull() } ?: TE.Type.BOLUS_WIZARD
            mgdlGlucose = obj["mgdlGlucose"]?.jsonPrimitive?.doubleOrNull
            glucoseType = obj["glucoseType"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { TE.MeterType.valueOf(it) }.getOrNull() }
        }
        return PendingCarbs(
            mainCarbs = mainCarbs,
            source = obj["source"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Sources.valueOf(it) }.getOrNull() } ?: Sources.WizardDialog,
            createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L
        )
    }

    companion object {
        private const val TIMEOUT_MS = 60 * 60 * 1000L // 1 hour
        private const val CLAIM_TIMEOUT_MS = 60 * 1000L // 1 minute
    }
}

data class PendingCarbs(
    val mainCarbs: DetailedBolusInfo,
    val source: Sources = Sources.WizardDialog,
    /** Stamped by [PendingCarbStore.setPending]; a value passed here is ignored. */
    val createdAt: Long = 0L
)

/** A claimed deferred entry; [token] identifies the claim for [PendingCarbStore.commitSucceeded]/`commitFailed`. */
data class PendingCarbClaim(val pending: PendingCarbs, val token: Long)
