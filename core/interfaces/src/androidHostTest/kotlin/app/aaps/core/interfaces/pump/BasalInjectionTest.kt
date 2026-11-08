package app.aaps.core.interfaces.pump

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BasalInjectionTest {

    private companion object {

        const val NOW = 1_700_000_000_000L
    }

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
    fun snapsDoseToWholeUnits() {
        assertThat(BasalInjection.snapDose(10.0)).isEqualTo(10.0)
        assertThat(BasalInjection.snapDose(10.4)).isEqualTo(10.0)
        assertThat(BasalInjection.snapDose(10.5)).isEqualTo(11.0)
        assertThat(BasalInjection.snapDose(10.6)).isEqualTo(11.0)
        assertThat(BasalInjection.snapDose(0.0)).isEqualTo(0.0)
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

    @Test
    fun permanentProfileSwitchStaysPermanent() {
        assertThat(BasalInjection.remainingProfileSwitchMinutes(duration = 0, end = 0, timestamp = NOW, now = NOW)).isEqualTo(0)
        // end is irrelevant for a permanent switch
        assertThat(BasalInjection.remainingProfileSwitchMinutes(duration = 0, end = NOW + 3_600_000, timestamp = NOW, now = NOW)).isEqualTo(0)
    }

    @Test
    fun remainingProfileSwitchDurationIsCarriedOver() {
        val duration = 24 * 3_600_000L
        // 6 h into a 24 h switch -> 18 h left (end not stored -> from timestamp + duration)
        assertThat(BasalInjection.remainingProfileSwitchMinutes(duration, end = 0, timestamp = NOW - 6 * 3_600_000, now = NOW))
            .isEqualTo(18 * 60)
        // a stored originalEnd wins over timestamp + duration
        assertThat(BasalInjection.remainingProfileSwitchMinutes(duration, end = NOW + 2 * 3_600_000, timestamp = NOW - 6 * 3_600_000, now = NOW))
            .isEqualTo(2 * 60)
    }

    @Test
    fun expiredProfileSwitchDoesNotProduceNegativeDuration() {
        assertThat(BasalInjection.remainingProfileSwitchMinutes(3_600_000, end = 0, timestamp = NOW - 2 * 3_600_000, now = NOW)).isEqualTo(0)
    }

    @Test
    fun expiredLimitedProfileSwitchIsNotCarriedOver() {
        // The EPS row survives its own end until KeepAliveWorker re-applies the requested profile.
        // Carrying it over with a 0 duration would freeze it permanently, so an expired limited
        // switch must be treated as "no switch" (percentage back to 100).
        val running = 2 * 3_600_000L
        assertThat(BasalInjection.isProfileSwitchCarryable(running, end = 0, timestamp = NOW - 3_600_000, now = NOW)).isTrue()
        assertThat(BasalInjection.isProfileSwitchCarryable(running, end = 0, timestamp = NOW - 3 * 3_600_000, now = NOW)).isFalse()
        // exactly at the end it is already over
        assertThat(BasalInjection.isProfileSwitchCarryable(running, end = 0, timestamp = NOW - running, now = NOW)).isFalse()
        // a permanent switch has no end - it is always carried over
        assertThat(BasalInjection.isProfileSwitchCarryable(0, end = 0, timestamp = NOW - 30 * 24 * 3_600_000L, now = NOW)).isTrue()
    }
}
