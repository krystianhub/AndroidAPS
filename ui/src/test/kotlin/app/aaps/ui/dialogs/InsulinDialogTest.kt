package app.aaps.ui.dialogs

import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure basal (Lantus) logic in [InsulinDialog.Companion]:
 * the profile-rewrite trigger and the dose parsing from NOTE therapy events.
 */
class InsulinDialogTest : TestBase() {

    // region basalMatchesProfile - any dose difference triggers the profile rewrite

    @Test
    fun identicalDoseDoesNotTriggerRewrite() {
        assertThat(InsulinDialog.basalMatchesProfile(amount = 8.0, previousDose = 8.0)).isTrue()
        assertThat(InsulinDialog.basalMatchesProfile(amount = 10.5, previousDose = 10.5)).isTrue()
    }

    @Test
    fun anyDoseDifferenceTriggersRewrite() {
        // regression: the original max(1U, 10%) tolerance silently accepted 8 -> 9 U (12.5 % change)
        assertThat(InsulinDialog.basalMatchesProfile(amount = 9.0, previousDose = 8.0)).isFalse()
        assertThat(InsulinDialog.basalMatchesProfile(amount = 8.0, previousDose = 9.0)).isFalse()
        // 0.05 U (smallest pen step differences) must trigger too
        assertThat(InsulinDialog.basalMatchesProfile(amount = 8.05, previousDose = 8.0)).isFalse()
        assertThat(InsulinDialog.basalMatchesProfile(amount = 7.95, previousDose = 8.0)).isFalse()
    }

    // endregion

    // region extractBasalDose - parses "Lantus xU" from NOTE therapy events

    @Test
    fun parsesSimpleNote() {
        assertThat(InsulinDialog.extractBasalDose("Lantus 8U")).isEqualTo(8.0)
        assertThat(InsulinDialog.extractBasalDose("Lantus 10.0U")).isEqualTo(10.0)
        assertThat(InsulinDialog.extractBasalDose("Lantus 10.0U pos 9")).isEqualTo(10.0)
    }

    @Test
    fun parsesDecimalCommaAndCaseInsensitive() {
        assertThat(InsulinDialog.extractBasalDose("lantus 8,5U")).isEqualTo(8.5)
        assertThat(InsulinDialog.extractBasalDose("LANTUS 12.25U")).isEqualTo(12.25)
    }

    @Test
    fun parsesNoteWithPrefixText() {
        assertThat(InsulinDialog.extractBasalDose("evening Lantus: 9U")).isEqualTo(9.0)
    }

    @Test
    fun ignoresNonBasalNotes() {
        assertThat(InsulinDialog.extractBasalDose("site change")).isNull()
        assertThat(InsulinDialog.extractBasalDose(null)).isNull()
    }

    @Test
    fun doseOutsideWordBoundariesIsIgnored() {
        // embedded in a longer word - not a standalone Lantus marker
        assertThat(InsulinDialog.extractBasalDose("Lantusx 8U")).isNull()
    }

    // endregion

    // region flatRate / flatRateTotal - the rate quantization the profile rewrite performs

    @Test
    fun flatRateRoundsToCentiUnitsPerHour() {
        assertThat(InsulinDialog.flatRate(amount = 8.0)).isEqualTo(0.33)   // 0.3333 -> 0.33
        assertThat(InsulinDialog.flatRate(amount = 10.0)).isEqualTo(0.42)  // 0.4167 -> 0.42
        assertThat(InsulinDialog.flatRate(amount = 7.2)).isEqualTo(0.30)   // exact
        assertThat(InsulinDialog.flatRate(amount = 0.0)).isEqualTo(0.01)   // floor guard
    }

    @Test
    fun flatRateTotalIsRateTimes24() {
        assertThat(InsulinDialog.flatRateTotal(amount = 8.0)).isEqualTo(7.92)   // 0.33 * 24
        assertThat(InsulinDialog.flatRateTotal(amount = 7.92)).isEqualTo(7.92)  // already-quantized dose is stable
        assertThat(InsulinDialog.flatRateTotal(amount = 10.08)).isEqualTo(10.08)
    }

    // endregion

    // region fallback comparison - profile-derived references compare against the quantized dose

    @Test
    fun quantizedDoseMatchesAlreadyFlatProfile() {
        // profile total 7.92 (from a past rewrite), pen dose 8 U -> quantized to 7.92 -> no rewrite
        assertThat(InsulinDialog.basalMatchesProfile(InsulinDialog.flatRateTotal(8.0), previousDose = 7.92)).isTrue()
    }

    @Test
    fun quantizedDoseStillTriggersOnRealDifference() {
        // profile total 7.92, pen dose 9 U -> quantized to 8.64 -> rewrite
        assertThat(InsulinDialog.basalMatchesProfile(InsulinDialog.flatRateTotal(9.0), previousDose = 7.92)).isFalse()
        // profile total 7.92, pen dose 7.5 U -> quantized to 7.44 -> rewrite
        assertThat(InsulinDialog.basalMatchesProfile(InsulinDialog.flatRateTotal(7.5), previousDose = 7.92)).isFalse()
    }

    @Test
    fun fpNoiseDoesNotTriggerRewrite() {
        // 0.1 + 0.2 != 0.3 in binary floating point - must still count as "same"
        assertThat(InsulinDialog.basalMatchesProfile(0.1 + 0.2, previousDose = 0.3)).isTrue()
    }

    // endregion
}
