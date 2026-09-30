package app.aaps.plugins.aps.openAPSSMB

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
 * MDI fork: the sensitivity ratio (autosens / TDD-based) must never scale the working basal —
 * basal is fixed by the long-acting injection. The ratio keeps adjusting glucose targets and
 * (outside DynISF) ISF, and the basal-based IOB model stays at the profile rate.
 */
class DetermineBasalSmbMdiTest : TestBaseWithProfile() {
    private lateinit var determineBasalSMB: DetermineBasalSMB

    @BeforeEach
    fun prepare() {
        determineBasalSMB = DetermineBasalSMB(profileUtil, fabricPrivacy)
    }

    private fun profile(basalAdjustmentAllowed: Boolean) =
        OapsProfile(
            dia = 5.0,
            min_5m_carbimpact = 3.0,
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
            basal_adjustment_allowed = basalAdjustmentAllowed,
        )

    private fun iobArray(): Array<IobTotal> {
        val now = dateUtil.now()
        return Array(48) { i ->
            val t = now + i * 5 * 60000L
            IobTotal(time = t, iob = 0.0, activity = 0.0, iobWithZeroTemp = IobTotal(time = t))
        }
    }

    private fun run(
        basalAdjustmentAllowed: Boolean,
        ratio: Double,
    ) = determineBasalSMB.determine_basal(
        glucose_status =
            GlucoseStatusSMB(
                glucose = 140.0,
                noise = 0.0,
                delta = 1.0,
                shortAvgDelta = 1.0,
                longAvgDelta = 1.0,
                date = dateUtil.now(),
            ),
        currenttemp = CurrentTemp(duration = 0, rate = 0.0, minutesrunning = null),
        iob_data_array = iobArray(),
        profile = profile(basalAdjustmentAllowed),
        autosens_data = AutosensResult(ratio = ratio),
        meal_data = MealData(),
        microBolusAllowed = false,
        currentTime = dateUtil.now(),
        flatBGsDetected = false,
        dynIsfMode = false,
    )

    @Test
    fun `mdi keeps basal fixed while ratio adjusts targets`() {
        // consoleLog is a shared mutable list inside the engine - snapshot it per run
        val mdi = run(basalAdjustmentAllowed = false, ratio = 1.2)
        val mdiLog = mdi.consoleLog!!.joinToString()
        val pump = run(basalAdjustmentAllowed = true, ratio = 1.2)
        val pumpLog = pump.consoleLog!!.joinToString()

        // target adjustment is retained in MDI
        assertThat(mdi.targetBG).isEqualTo(pump.targetBG)
        assertThat(mdi.targetBG).isNotEqualTo(100.0)
        // insulinReq is unchanged - only the hypothetical basal component of the temp rate differs
        assertThat(mdi.insulinReq).isEqualTo(pump.insulinReq)
        // pump scales the working basal with the ratio (1.2 x 1.0 U/h), MDI does not
        assertThat(pump.rate!!).isWithin(0.001).of(mdi.rate!! + 0.2)
        assertThat(mdiLog).contains("Basal fixed at")
        assertThat(pumpLog).contains("Adjusting basal")
    }

    @Test
    fun `neutral ratio produces identical output in mdi and pump mode`() {
        val mdi = run(basalAdjustmentAllowed = false, ratio = 1.0)
        val pump = run(basalAdjustmentAllowed = true, ratio = 1.0)
        assertThat(mdi.rate).isEqualTo(pump.rate)
        assertThat(mdi.targetBG).isEqualTo(pump.targetBG)
        assertThat(mdi.insulinReq).isEqualTo(pump.insulinReq)
    }
}
