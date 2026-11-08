package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.ui.compose.StatusLevel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Pins the pre-4.0 predicted-BG colour model. */
class PredictedBgLevelTest {

    @Test
    fun `no eventual BG has no colour`() {
        assertThat(predictedBgLevel(null, 100.0, 80.0, 180.0)).isEqualTo(StatusLevel.UNSPECIFIED)
    }

    @Test
    fun `landing value outside the stats band is critical`() {
        assertThat(predictedBgLevel(200.0, null, 80.0, 180.0)).isEqualTo(StatusLevel.CRITICAL)
        assertThat(predictedBgLevel(60.0, null, 80.0, 180.0)).isEqualTo(StatusLevel.CRITICAL)
    }

    @Test
    fun `a predicted dip below the hypo band is critical even with an in-range landing`() {
        assertThat(predictedBgLevel(150.0, 60.0, 80.0, 180.0)).isEqualTo(StatusLevel.CRITICAL)
    }

    @Test
    fun `in-target landing without a dip below target is normal`() {
        assertThat(predictedBgLevel(150.0, 120.0, 80.0, 180.0)).isEqualTo(StatusLevel.NORMAL)
        assertThat(predictedBgLevel(150.0, null, 80.0, 180.0)).isEqualTo(StatusLevel.NORMAL)
    }

    @Test
    fun `a dip below target low is a warning`() {
        assertThat(predictedBgLevel(150.0, 75.0, 80.0, 180.0)).isEqualTo(StatusLevel.WARNING)
    }

    @Test
    fun `landing outside the profile target is a warning`() {
        assertThat(predictedBgLevel(150.0, 140.0, 160.0, 200.0)).isEqualTo(StatusLevel.WARNING)
        assertThat(predictedBgLevel(150.0, 140.0, null, null)).isEqualTo(StatusLevel.WARNING)
    }
}
