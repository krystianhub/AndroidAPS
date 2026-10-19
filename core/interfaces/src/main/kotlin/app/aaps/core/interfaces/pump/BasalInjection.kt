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
}
