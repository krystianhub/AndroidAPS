package app.aaps.core.interfaces.pump

import app.aaps.core.interfaces.utils.Round
import kotlin.math.max

/**
 * Helper for MDI long-acting (basal) insulin handling.
 *
 * The dose is recorded as a NOTE therapy event ("Lantus xU ..."), so it is persisted
 * in the local database and synced to Nightscout without inflating TDD/IOB.
 */
object BasalInjection {

    private val DOSE_REGEX = Regex("(?i)\\bLantus\\s*[:#]?\\s*([0-9]+(?:[.,][0-9]+)?)\\s*U\\b")

    /** Extracts the recorded dose from a NOTE therapy event, if present. */
    fun extractDose(note: String?): Double? =
        note?.let { DOSE_REGEX.find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }

    /** Flat U/h rate the profile stores for a dose. */
    fun flatRate(amount: Double): Double =
        max(Round.roundTo(amount / 24.0, 0.01), 0.01)

    /** 24 h total represented by the flat rate stored for a dose. */
    fun flatRateTotal(amount: Double): Double =
        flatRate(amount) * 24.0

    /** Any difference (beyond FP noise) means the profile does not reflect the recorded dose. */
    fun matchesProfile(amount: Double, previousDose: Double): Boolean =
        Round.isSame(amount, previousDose)

    /** Remaining duration of a running switch in minutes; 0 for a permanent switch (must stay permanent). */
    fun remainingProfileSwitchMinutes(duration: Long, end: Long, timestamp: Long, now: Long): Int {
        if (duration == 0L) return 0
        // originalEnd is not always filled in - it is timestamp + duration
        val endTime = if (end > 0) end else timestamp + duration
        return max(0L, (endTime - now) / 60000L).toInt()
    }

    /** A permanent switch, or a limited one that has not ended yet - anything worth carrying over. */
    fun isProfileSwitchCarryable(duration: Long, end: Long, timestamp: Long, now: Long): Boolean =
        duration == 0L || remainingProfileSwitchMinutes(duration, end, timestamp, now) > 0
}
