package app.aaps.core.interfaces.pump

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BasalInjectionTest {

    @Test
    fun extractsDoseFromNote() {
        assertThat(BasalInjection.extractDose("Lantus 8U")).isEqualTo(8.0)
        assertThat(BasalInjection.extractDose("Lantus 10.0U pos 9")).isEqualTo(10.0)
        assertThat(BasalInjection.extractDose("lantus 8,5U")).isEqualTo(8.5)
        assertThat(BasalInjection.extractDose("evening Lantus: 9U")).isEqualTo(9.0)
    }

    @Test
    fun ignoresNonBasalNotes() {
        assertThat(BasalInjection.extractDose("site change")).isNull()
        assertThat(BasalInjection.extractDose(null)).isNull()
        assertThat(BasalInjection.extractDose("Lantusx 8U")).isNull()
    }

    @Test
    fun flatRateRoundsToCentiUnitsPerHour() {
        assertThat(BasalInjection.flatRate(8.0)).isEqualTo(0.33)
        assertThat(BasalInjection.flatRate(10.0)).isEqualTo(0.42)
        assertThat(BasalInjection.flatRate(7.2)).isEqualTo(0.30)
        assertThat(BasalInjection.flatRate(0.0)).isEqualTo(0.01)
    }

    @Test
    fun flatRateTotalIsRateTimes24() {
        assertThat(BasalInjection.flatRateTotal(8.0)).isEqualTo(7.92)
        assertThat(BasalInjection.flatRateTotal(7.92)).isEqualTo(7.92)
    }

    @Test
    fun matchesProfileComparesQuantizedDose() {
        // profile total 7.92 (from a past rewrite), pen dose 8 U -> quantized to 7.92 -> no update
        assertThat(BasalInjection.matchesProfile(BasalInjection.flatRateTotal(8.0), 7.92)).isTrue()
        // profile total 7.92, pen dose 9 U -> quantized to 8.64 -> update
        assertThat(BasalInjection.matchesProfile(BasalInjection.flatRateTotal(9.0), 7.92)).isFalse()
        // FP noise must not count as a difference
        assertThat(BasalInjection.matchesProfile(0.1 + 0.2, 0.3)).isTrue()
    }
}
