package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.time.T
import app.aaps.core.ui.compose.StatusLevel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the last-bolus status light to the pre-4.0 MDI behaviour, using the curve of the profile's
 * NovoRapid (peak 75 min, DIA 5 h, U100).
 */
class BolusStatusLevelTest {

    private val novoRapid = ICfg(insulinLabel = "NovoRapid", peak = 75, dia = 5.0, concentration = 1.0)
    private val bolusTime = 1_700_000_000_000L

    private fun bolus(amount: Double = 1.0) =
        BS(timestamp = bolusTime, amount = amount, type = BS.Type.NORMAL, iCfg = novoRapid)

    private fun at(minutes: Long): Long = bolusTime + T.mins(minutes).msecs()

    @Test
    fun `NovoRapid follows the pre-4 status-light thresholds`() {
        val bolus = bolus()
        assertThat(bolusStatusLevel(bolus, at(0))).isEqualTo(StatusLevel.UNSPECIFIED)   // ratio 0, before peak
        assertThat(bolusStatusLevel(bolus, at(1))).isEqualTo(StatusLevel.UNSPECIFIED)   // just injected
        assertThat(bolusStatusLevel(bolus, at(10))).isEqualTo(StatusLevel.WARNING)      // ramping up
        assertThat(bolusStatusLevel(bolus, at(30))).isEqualTo(StatusLevel.WARNING)      // still ramping up
        assertThat(bolusStatusLevel(bolus, at(40))).isEqualTo(StatusLevel.NORMAL)       // >= 80% of peak activity
        assertThat(bolusStatusLevel(bolus, at(75))).isEqualTo(StatusLevel.NORMAL)       // peak
        assertThat(bolusStatusLevel(bolus, at(120))).isEqualTo(StatusLevel.NORMAL)      // still >= 80%
        assertThat(bolusStatusLevel(bolus, at(150))).isEqualTo(StatusLevel.WARNING)     // fading
        assertThat(bolusStatusLevel(bolus, at(240))).isEqualTo(StatusLevel.WARNING)     // 4 h, under 20% left
        assertThat(bolusStatusLevel(bolus, at(270))).isEqualTo(StatusLevel.CRITICAL)    // under 10% after peak
        assertThat(bolusStatusLevel(bolus, at(300))).isEqualTo(StatusLevel.CRITICAL)    // DIA reached
        assertThat(bolusStatusLevel(bolus, at(360))).isEqualTo(StatusLevel.CRITICAL)
    }

    @Test
    fun `matches the pre-4 curve across the whole action time`() {
        val bolus = bolus()
        for (minutes in 0L..360L step 5) {
            assertThat(bolusStatusLevel(bolus, at(minutes)))
                .isEqualTo(pre4BolusLevel(bolus, at(minutes)))
        }
    }

    @Test
    fun `a zero amount has no colour`() {
        assertThat(bolusStatusLevel(bolus(amount = 0.0), at(75))).isEqualTo(StatusLevel.UNSPECIFIED)
    }

    /** The pre-4.0 MDI implementation, kept here as the reference the fork must not drift from. */
    private fun pre4BolusLevel(bolus: BS, now: Long): StatusLevel {
        val iCfg = bolus.iCfg
        val peakTime = bolus.timestamp + T.mins(iCfg.peak.toLong()).msecs()
        fun fraction(at: Long): Double = iCfg.iobCalcForTreatment(bolus, at).activityContrib / bolus.amount
        val fMax = fraction(peakTime)
        val ratio = if (fMax > 0) fraction(now) / fMax else 0.0
        val afterPeak = now >= peakTime
        return when {
            ratio >= 0.8 -> StatusLevel.NORMAL
            ratio < 0.1  -> if (afterPeak) StatusLevel.CRITICAL else StatusLevel.UNSPECIFIED
            else         -> StatusLevel.WARNING
        }
    }
}
