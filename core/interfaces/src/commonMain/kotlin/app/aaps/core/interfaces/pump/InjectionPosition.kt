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
     * Position of the newest injection carrying one (`pos x` in a bolus or NOTE therapy event).
     * Records without a position do not consume the [maxLookback] budget, and the lists may arrive
     * in either order (the DAO `ascending` flag is effectively inverted).
     */
    fun findLastPosition(boluses: List<BS>, therapyEvents: List<TE> = emptyList(), maxLookback: Int = 3): Int? =
        (boluses.asSequence()
            .filter { it.type != BS.Type.PRIMING }
            .mapNotNull { bolus -> extractFromNotes(bolus.notes)?.let { position -> InjectionRecord(bolus.timestamp, position) } } +
            therapyEvents.asSequence()
                .filter { it.type == TE.Type.NOTE }
                .mapNotNull { event -> extractFromNotes(event.note)?.let { position -> InjectionRecord(event.timestamp, position) } })
            .sortedByDescending { it.timestamp }
            .take(maxLookback)
            .map { it.position }
            .firstOrNull()

    private data class InjectionRecord(val timestamp: Long, val position: Int)

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