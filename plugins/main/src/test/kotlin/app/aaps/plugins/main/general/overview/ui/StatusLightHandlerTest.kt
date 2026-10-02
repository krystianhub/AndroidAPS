package app.aaps.plugins.main.general.overview.ui

import app.aaps.core.interfaces.aps.Predictions
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StatusLightHandlerTest {

    @Test
    fun minPredictedBgReturnsLowestValueAcrossCurves() {
        val predictions = Predictions(
            IOB = listOf(120, 110, 95),
            ZT = listOf(120, 100, 82),
            COB = listOf(120, 140, 160),
            aCOB = null,
            UAM = null
        )
        assertThat(StatusLightHandler.minPredictedBg(predictions)).isEqualTo(82.0)
    }

    @Test
    fun minPredictedBgIsNullWithoutPredictions() {
        assertThat(StatusLightHandler.minPredictedBg(Predictions())).isNull()
    }

    @Test
    fun colorIsNeutralWithoutEventualBg() {
        assertThat(StatusLightHandler.eventualBgColorAttr(null, 82.0, 100.0..110.0))
            .isEqualTo(app.aaps.core.ui.R.attr.defaultTextColor)
    }

    @Test
    fun colorIsUrgentWhenLandingIsOutOfStatsRange() {
        assertThat(StatusLightHandler.eventualBgColorAttr(65.0, null, 100.0..110.0))
            .isEqualTo(app.aaps.core.ui.R.attr.urgentColor)
        assertThat(StatusLightHandler.eventualBgColorAttr(250.0, null, 100.0..110.0))
            .isEqualTo(app.aaps.core.ui.R.attr.urgentColor)
    }

    @Test
    fun colorIsUrgentWhenPredictionDipsBelowHypoThreshold() {
        // landing in target, but the curve dips low first - and in MDI no microbolus will smooth that dip
        assertThat(StatusLightHandler.eventualBgColorAttr(105.0, 65.0, 100.0..110.0))
            .isEqualTo(app.aaps.core.ui.R.attr.urgentColor)
    }

    @Test
    fun colorIsWarningWhenPredictionDipsBelowTarget() {
        assertThat(StatusLightHandler.eventualBgColorAttr(105.0, 95.0, 100.0..110.0))
            .isEqualTo(app.aaps.core.ui.R.attr.metadataTextWarningColor)
    }

    @Test
    fun colorIsOkWhenLandingInTargetAndNoDip() {
        assertThat(StatusLightHandler.eventualBgColorAttr(105.0, 102.0, 100.0..110.0))
            .isEqualTo(app.aaps.core.ui.R.attr.metadataTextOkColor)
        assertThat(StatusLightHandler.eventualBgColorAttr(105.0, null, 100.0..110.0))
            .isEqualTo(app.aaps.core.ui.R.attr.metadataTextOkColor)
    }

    @Test
    fun colorIsWarningWhenLandingOutsideTarget() {
        assertThat(StatusLightHandler.eventualBgColorAttr(150.0, 140.0, 100.0..110.0))
            .isEqualTo(app.aaps.core.ui.R.attr.metadataTextWarningColor)
    }
}
