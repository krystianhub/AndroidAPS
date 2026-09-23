package app.aaps.core.interfaces.pump

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE

/**
 * Helper for MDI injection-position tracking.
 *
 * The position (1-12, like hours on a clock) is recorded as a "pos x" note
 * attached to the bolus record, so it is persisted in the local database and
 * synced to Nightscout together with the treatment (same as notes entered
 * manually in xDrip).
 */
object InjectionPosition {

    const val MAX_POSITION = 12

    private val POSITION_REGEX = Regex("(?i)\\bpos\\s*[:#]?\\s*(\\d{1,2})\\b")

    /** Extracts the injection position (1..[MAX_POSITION]) from notes, if present. */
    fun extractFromNotes(notes: String?): Int? =
        notes?.let {
            POSITION_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { position -> position in 1..MAX_POSITION }
        }

    /**
     * Finds the most recent recorded injection position by looking back through
     * the last [maxLookback] injections (boluses and NOTE therapy events such as
     * recorded Lantus injections), regardless of the order the lists arrive in
     * (the `ascending` flag of the DAO queries is effectively inverted, so callers
     * pass lists in either order). The lists are merged and explicitly sorted
     * newest-first by timestamp before the lookback, so only the [maxLookback]
     * most recent injections are inspected. Injections without a position (e.g.
     * into a limb not covered by the numbering) are skipped.
     */
    fun findLastPosition(boluses: List<BS>, therapyEvents: List<TE> = emptyList(), maxLookback: Int = 3): Int? =
        (boluses.asSequence()
            .filter { it.type != BS.Type.PRIMING }
            .map { InjectionRecord(it.timestamp, extractFromNotes(it.notes)) } +
            therapyEvents.asSequence()
                .filter { it.type == TE.Type.NOTE }
                .map { InjectionRecord(it.timestamp, extractFromNotes(it.note)) })
            .sortedByDescending { it.timestamp }
            .take(maxLookback)
            .map { it.position }
            .firstOrNull { it != null }

    private data class InjectionRecord(val timestamp: Long, val position: Int?)

    /** Suggests the next injection position, rotating 1..[MAX_POSITION]. */
    fun suggestNext(lastPosition: Int?): Int? =
        lastPosition?.let { if (it >= MAX_POSITION) 1 else it + 1 }

    /**
     * Appends "pos x" to notes, replacing any previously recorded position
     * so the record always carries the position actually used.
     */
    fun appendToNotes(notes: String, position: Int): String {
        require(position in 1..MAX_POSITION)
        val cleaned = stripPosition(notes)
        val prefix = if (cleaned.isBlank()) "" else "$cleaned "
        return "${prefix}pos $position"
    }

    /**
     * Removes any recorded position from notes, leaving the remaining text
     * (used to keep "pos x" off records it does not belong to, e.g. meals).
     */
    fun stripPosition(notes: String): String =
        notes.replace(POSITION_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(',', ';')
}