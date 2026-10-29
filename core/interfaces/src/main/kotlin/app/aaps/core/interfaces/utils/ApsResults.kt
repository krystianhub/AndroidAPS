package app.aaps.core.interfaces.utils

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.db.PersistenceLayer
import kotlin.math.abs

/**
 * Recent APS results (persisted + run in progress) and statistics over them.
 * Shared by the Overview status lights and the MDI pen-bolus suggestion.
 */
object ApsResults {

    /** A persisted result younger than this is the run in progress; the live twin replaces it. */
    private val PERSIST_DELAY_MS = T.mins(1).msecs()

    /**
     * Persisted results within [windowMs] before [now], merged with the live run
     * (`timestamp to result`, null when none). A persisted twin of the live run is dropped,
     * whichever of the two timestamps is the older one.
     */
    fun recent(persistenceLayer: PersistenceLayer, now: Long, windowMs: Long, live: Pair<Long, APSResult>? = null): List<Pair<Long, APSResult>> {
        val windowStart = now - windowMs
        val persisted = try {
            persistenceLayer.getApsResults(windowStart, now)
                .filter { it.date in windowStart..now }
                .map { it.date to it }
        } catch (_: Exception) {
            emptyList()
        }
        val combined = (persisted + listOfNotNull(live)).associateBy { it.first }.toMutableMap()
        live?.let { (timestamp, result) ->
            persisted.firstOrNull { abs(timestamp - it.first) <= PERSIST_DELAY_MS }
                ?.let { combined.remove(it.first) }
            combined[timestamp] = timestamp to result
        }
        return combined.values.toList()
    }

    /** Median insulinReq of runs within [windowMs] before [windowEnd]; null when none. */
    fun medianInsulinReq(results: List<Pair<Long, APSResult>>, windowEnd: Long, windowMs: Long): Double? =
        median(
            results.asSequence()
                .filter { (timestamp, _) -> timestamp in (windowEnd - windowMs)..windowEnd }
                .mapNotNull { (_, result) -> result.insulinReq?.takeIf { it.isFinite() } }
                .toList()
        )

    /** Median of [values]; even sizes average the two middle values. Null when empty. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
