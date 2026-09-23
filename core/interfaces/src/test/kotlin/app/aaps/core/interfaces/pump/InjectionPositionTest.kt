package app.aaps.core.interfaces.pump

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class InjectionPositionTest {

    private fun bolus(timestamp: Long, notes: String?, type: BS.Type = BS.Type.NORMAL) =
        BS(timestamp = timestamp, amount = 1.0, type = type, notes = notes)

    private fun note(timestamp: Long, text: String?, type: TE.Type = TE.Type.NOTE) =
        TE(timestamp = timestamp, type = type, note = text, glucoseUnit = GlucoseUnit.MGDL)

    @Test
    fun findsPositionFromNewestBolus() {
        val boluses = listOf(
            bolus(1_000, null),
            bolus(2_000, "pos 7"),
            bolus(3_000, "pos 8")
        )
        assertThat(InjectionPosition.findLastPosition(boluses)).isEqualTo(8)
    }

    @Test
    fun ignoresOlderBolusesWithPosition() {
        val boluses = listOf(
            bolus(1_000, "pos 6"),
            bolus(2_000, null),
            bolus(3_000, "pos 7"),
            bolus(4_000, null),
            bolus(5_000, "pos 8")
        )
        assertThat(InjectionPosition.findLastPosition(boluses)).isEqualTo(8)
    }

    // getBolusesFromTimeToTime(ascending = false) returns oldest-first
    // (DAO already sorts newest-first, repository reverses for !ascending) -
    // the result must not depend on the arrival order.
    @Test
    fun resultIndependentOfListOrder() {
        val oldestFirst = listOf(
            bolus(1_000, "pos 7"),
            bolus(2_000, "pos 8")
        )
        val newestFirst = oldestFirst.reversed()
        assertThat(InjectionPosition.findLastPosition(oldestFirst)).isEqualTo(8)
        assertThat(InjectionPosition.findLastPosition(newestFirst)).isEqualTo(8)
    }

    @Test
    fun skipsPrimingRecords() {
        val boluses = listOf(
            bolus(1_000, "pos 7"),
            bolus(2_000, "pos 9", type = BS.Type.PRIMING)
        )
        assertThat(InjectionPosition.findLastPosition(boluses)).isEqualTo(7)
    }

    @Test
    fun skipsBolusesWithoutPositionInLookbackWindow() {
        val boluses = listOf(
            bolus(1_000, "pos 7"),   // oldest - outside a 3-bolus lookback
            bolus(2_000, null),
            bolus(3_000, null),
            bolus(4_000, null)
        )
        assertThat(InjectionPosition.findLastPosition(boluses, maxLookback = 3)).isNull()
        // ...but found with a larger window
        assertThat(InjectionPosition.findLastPosition(boluses, maxLookback = 4)).isEqualTo(7)
    }

    @Test
    fun emptyListReturnsNull() {
        assertThat(InjectionPosition.findLastPosition(emptyList())).isNull()
    }

    // Lantus injections are recorded as NOTE therapy events, not boluses -
    // they must participate in the position lookback.
    @Test
    fun findsPositionFromLantusNote() {
        assertThat(
            InjectionPosition.findLastPosition(
                emptyList(),
                listOf(note(1_000, "Lantus 10.0U pos 9"))
            )
        ).isEqualTo(9)
    }

    @Test
    fun findsMostRecentAcrossBolusesAndNotes() {
        assertThat(
            InjectionPosition.findLastPosition(
                listOf(bolus(1_000, "pos 5"), bolus(5_000, "pos 8")),
                listOf(note(3_000, "Lantus 10.0U pos 9"))
            )
        ).isEqualTo(8)
        // same data, NOTE most recent
        assertThat(
            InjectionPosition.findLastPosition(
                listOf(bolus(1_000, "pos 5"), bolus(3_000, "pos 8")),
                listOf(note(5_000, "Lantus 10.0U pos 9"))
            )
        ).isEqualTo(9)
    }

    @Test
    fun emptyNoteDoesNotStopLookback() {
        assertThat(
            InjectionPosition.findLastPosition(
                listOf(bolus(1_000, "pos 5")),
                listOf(note(2_000, "Lantus 10.0U"))
            )
        ).isEqualTo(5)
    }

    @Test
    fun ignoresNonNoteTherapyEvents() {
        assertThat(
            InjectionPosition.findLastPosition(
                emptyList(),
                listOf(note(1_000, "pos 9", type = TE.Type.EXERCISE))
            )
        ).isNull()
    }

    @Test
    fun suggestsNextWrappingAtMax() {
        assertThat(InjectionPosition.suggestNext(null)).isNull()
        assertThat(InjectionPosition.suggestNext(7)).isEqualTo(8)
        assertThat(InjectionPosition.suggestNext(InjectionPosition.MAX_POSITION)).isEqualTo(1)
    }

    @Test
    fun extractsPositionFromNotes() {
        assertThat(InjectionPosition.extractFromNotes("pos 8")).isEqualTo(8)
        assertThat(InjectionPosition.extractFromNotes("Pos: 3 and something else")).isEqualTo(3)
        assertThat(InjectionPosition.extractFromNotes("lunch pos 12")).isEqualTo(12)
        assertThat(InjectionPosition.extractFromNotes(null)).isNull()
        assertThat(InjectionPosition.extractFromNotes("no position here")).isNull()
        assertThat(InjectionPosition.extractFromNotes("pos 13")).isNull() // out of range
    }

    @Test
    fun appendsToNotesReplacingExistingPosition() {
        assertThat(InjectionPosition.appendToNotes("", 8)).isEqualTo("pos 8")
        assertThat(InjectionPosition.appendToNotes("snack", 8)).isEqualTo("snack pos 8")
        assertThat(InjectionPosition.appendToNotes("pos 7 snack", 8)).isEqualTo("snack pos 8")
        assertThat(InjectionPosition.appendToNotes("snack pos 3", 8)).isEqualTo("snack pos 8")
    }
}
