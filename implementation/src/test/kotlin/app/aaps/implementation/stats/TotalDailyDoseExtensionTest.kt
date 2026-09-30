package app.aaps.implementation.stats

import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

class TotalDailyDoseExtensionTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper

    private fun tdd(carbs: Double = 0.0, bolus: Double = 0.0) =
        TDD(timestamp = 0L, carbs = carbs, bolusAmount = bolus)

    @Test
    fun `icRatio is carbs per bolus`() {
        assertThat(tdd(carbs = 85.0, bolus = 10.0).icRatio!!).isWithin(1e-9).of(8.5)
        assertThat(tdd(carbs = 145.0, bolus = 15.5).icRatio!!).isWithin(1e-9).of(145.0 / 15.5)
    }

    @Test
    fun `icRatio is null without bolus`() {
        assertThat(tdd(carbs = 85.0, bolus = 0.0).icRatio).isNull()
    }

    @Test
    fun `icRatio is null without carbs`() {
        assertThat(tdd(carbs = 0.0, bolus = 10.0).icRatio).isNull()
    }

    @Test
    fun `icRatio is null when both missing`() {
        assertThat(tdd().icRatio).isNull()
    }

    @Test
    fun `icRatio is null for NaN values`() {
        assertThat(tdd(carbs = Double.NaN, bolus = 10.0).icRatio).isNull()
        assertThat(tdd(carbs = 85.0, bolus = Double.NaN).icRatio).isNull()
    }

    @Test
    fun `icRatioText formats ratio with one decimal`() {
        whenever(rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)).thenReturn("n/a")
        assertThat(tdd(carbs = 85.0, bolus = 10.0).icRatioText(rh)).isEqualTo("%.1f".format(8.5))
    }

    @Test
    fun `icRatioText falls back to n a when not calculable`() {
        whenever(rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)).thenReturn("n/a")
        assertThat(tdd().icRatioText(rh)).isEqualTo("n/a")
    }
}
