package app.aaps.plugins.aps.openAPSAMA

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * MDI: AMA must set `insulinReq` on every path (the pen suggestion is sized from it) and its
 * projected-low guard must cover every prediction curve from index 0.
 */
class DetermineBasalAmaMdiTest : TestBaseWithProfile() {

    private lateinit var determineBasalAMA: DetermineBasalAMA

    @BeforeEach
    fun prepare() {
        determineBasalAMA = DetermineBasalAMA(profileUtil)
    }

    private fun profile(min5mCarbImpact: Double = 3.0) =
        OapsProfile(
            dia = 5.0,
            min_5m_carbimpact = min5mCarbImpact,
            max_iob = 8.0,
            max_daily_basal = 1.0,
            max_basal = 10.0,
            min_bg = 100.0,
            max_bg = 100.0,
            target_bg = 100.0,
            carb_ratio = 10.0,
            sens = 50.0,
            autosens_adjust_targets = false,
            max_daily_safety_multiplier = 1000.0,
            current_basal_safety_multiplier = 1000.0,
            high_temptarget_raises_sensitivity = false,
            low_temptarget_lowers_sensitivity = false,
            sensitivity_raises_target = true,
            resistance_lowers_target = true,
            adv_target_adjustments = false,
            exercise_mode = false,
            half_basal_exercise_target = 160,
            maxCOB = 120,
            skip_neutral_temps = false,
            remainingCarbsCap = 90,
            enableUAM = false,
            A52_risk_enable = false,
            SMBInterval = 3,
            enableSMB_with_COB = false,
            enableSMB_with_temptarget = false,
            allowSMB_with_high_temptarget = false,
            enableSMB_always = false,
            enableSMB_after_carbs = false,
            maxSMBBasalMinutes = 30,
            maxUAMSMBBasalMinutes = 30,
            bolus_increment = 0.5,
            carbsReqThreshold = 20,
            current_basal = 1.0,
            temptargetSet = false,
            autosens_max = 1.2,
            out_units = "mg/dl",
            lgsThreshold = null,
            variable_sens = 0.0,
            insulinDivisor = 55,
            TDD = 0.0,
            basal_adjustment_allowed = false,
        )

    private fun iobArray(iob: Double = 0.0, activity: Double = 0.0): Array<IobTotal> {
        val now = dateUtil.now()
        return Array(48) { i ->
            val t = now + i * 5 * 60000L
            IobTotal(time = t, iob = iob, activity = activity, iobWithZeroTemp = IobTotal(time = t, activity = activity))
        }
    }

    private fun run(
        glucose: Double = 100.0,
        delta: Double = 0.0,
        bgAgeMinutes: Long = 0,
        iob: Double = 0.0,
        activity: Double = 0.0,
        mealData: MealData = MealData(),
        min5mCarbImpact: Double = 3.0,
    ) = determineBasalAMA.determine_basal(
        glucose_status = GlucoseStatusSMB(
            glucose = glucose,
            noise = 0.0,
            delta = delta,
            shortAvgDelta = delta,
            longAvgDelta = delta,
            date = dateUtil.now() - bgAgeMinutes * 60000L,
        ),
        currenttemp = CurrentTemp(duration = 0, rate = 0.0, minutesrunning = null),
        iob_data_array = iobArray(iob = iob, activity = activity),
        profile = profile(min5mCarbImpact),
        autosens_data = AutosensResult(ratio = 1.0),
        meal_data = mealData,
        currentTime = dateUtil.now(),
    )

    @Test
    fun `in-range run exposes insulinReq as zero instead of null`() {
        // the MDI smoothing median drops nulls - an in-range run must contribute "no insulin needed"
        val inRange = run()
        assertThat(inRange.insulinReq).isEqualTo(0.0)
        assertThat(inRange.predictedLow).isFalse()
    }

    @Test
    fun `predictedLow covers the IOB curve within the first 45 minutes`() {
        // activity 0.05 x 50 mg/dL/U x 5 min = -12.5 mg/dL per 5 min: the IOB curve drops below the
        // 75 mg/dL threshold after ~17 ticks. The carb impact (15 mg/dL/5m) keeps the COB curve
        // above it for the whole DIA, and minPredBG only samples that curve after 45 min.
        val dipping = run(glucose = 200.0, activity = 0.05, mealData = MealData(carbs = 10.0, mealCOB = 200.0), min5mCarbImpact = 15.0)
        assertThat(dipping.predBGs?.IOB?.min()).isLessThan(75)
        assertThat(dipping.predBGs?.COB).isNotNull()
        assertThat(dipping.predBGs?.COB!!.drop(9).min()).isGreaterThan(75)
        assertThat(dipping.predictedLow).isTrue()
    }

    @Test
    fun `notifications are guarded when the CGM is in an error state`() {
        // bg < 39: CGM calibrating / ??? - no prediction is computed, so never suggest insulin
        val errorState = run(glucose = 20.0, delta = 0.0)
        assertThat(errorState.predictedLow).isTrue()
    }
}
