package app.aaps.core.interfaces.pump

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.time.T
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the timestamps of the records [DetailedBolusInfo] writes for a treatment that carries both
 * insulin and carbs.
 *
 * Nightscout v3 derives a treatment's identifier from `date` + `eventType` (there is no client
 * identifier), so a bolus and its carbs stored in the same millisecond collapse into one document
 * there: the second upload replaces the first, and because the carbs are uploaded after the bolus,
 * the insulin is the one that disappears. [DetailedBolusInfo.createCarbs] offsets the carbs by one
 * millisecond when they would land on the bolus timestamp.
 */
class DetailedBolusInfoTimestampTest {

    private val iCfg = ICfg(insulinLabel = "Test", insulinEndTime = T.hours(5).msecs(), insulinPeakTime = T.mins(75).msecs(), concentration = 1.0)

    private fun treatment(timestamp: Long) = DetailedBolusInfo().apply {
        insulin = 3.0
        carbs = 32.0
        this.timestamp = timestamp
    }

    @Test
    fun `carbs at the same millisecond as the bolus move 1 ms later`() {
        val now = 1_700_000_000_000L
        val info = treatment(now)

        val bolus = info.createBolus(iCfg)
        val carbs = info.createCarbs()

        assertThat(bolus.timestamp).isEqualTo(now)
        assertThat(carbs.timestamp).isEqualTo(now + 1)
    }

    @Test
    fun `carbs already timestamped apart keep their own timestamp`() {
        val now = 1_700_000_000_000L
        val carbsTime = now + T.mins(15).msecs()
        val info = treatment(now).apply { carbsTimestamp = carbsTime }

        assertThat(info.createBolus(iCfg).timestamp).isEqualTo(now)
        assertThat(info.createCarbs().timestamp).isEqualTo(carbsTime)
    }

    @Test
    fun `carbs are not moved when the bolus was recorded at another time`() {
        val now = 1_700_000_000_000L
        val bolusTime = now + T.secs(5).msecs()
        val info = treatment(now).apply { bolusTimestamp = bolusTime }

        assertThat(info.createBolus(iCfg).timestamp).isEqualTo(bolusTime)
        assertThat(info.createCarbs().timestamp).isEqualTo(now)
    }

    @Test
    fun `carbs-only entry keeps its timestamp`() {
        val now = 1_700_000_000_000L
        val info = DetailedBolusInfo().apply {
            carbs = 32.0
            timestamp = now
        }

        assertThat(info.createCarbs().timestamp).isEqualTo(now)
    }
}
