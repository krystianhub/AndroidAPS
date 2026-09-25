package app.aaps.core.objects.wizard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MealMacroPlanTest {

    private val params = MealMacroPlan.DEFAULT_PARAMS

    @Test
    fun `zero macros return null plan - upstream wizard behavior`() {
        assertThat(MealMacroPlan.compute(carbs = 80, fat = 0, protein = 0, params = params)).isNull()
        assertThat(MealMacroPlan.compute(carbs = 0, fat = 0, protein = 0, params = params)).isNull()
    }

    @Test
    fun `trace macros below activation thresholds fall back to upstream logic`() {
        // splash of oil in the pan: 1 g fat, no protein -> no plan, plain wizard
        assertThat(MealMacroPlan.compute(carbs = 50, fat = 1, protein = 0, params = params)).isNull()
        // small yogurt: 2 g fat, 6 g protein -> no plan
        assertThat(MealMacroPlan.compute(carbs = 15, fat = 2, protein = 6, params = params)).isNull()
        // either macro reaching its threshold activates the plan
        assertThat(MealMacroPlan.compute(carbs = 50, fat = 5, protein = 0, params = params)).isNotNull()
        assertThat(MealMacroPlan.compute(carbs = 50, fat = 0, protein = 10, params = params)).isNotNull()
    }

    @Test
    fun `misconfigured heavy above lean does not crash and degrades gracefully`() {
        val swapped = params.copy(upfrontPctLean = 60, upfrontPctHeavy = 100)
        val plan = MealMacroPlan.compute(carbs = 100, fat = 60, protein = 120, params = swapped)!!
        assertThat(plan.suggestedUpfrontPercentage).isAtMost(60)
        assertThat(plan.suggestedUpfrontPercentage).isAtLeast(0)
    }

    @Test
    fun `carbs are conserved between upfront and primary tail`() {
        val plan = MealMacroPlan.compute(carbs = 131, fat = 47, protein = 53, params = params)!!
        val slowCarbs = plan.primaryTailCarbs - (53 * 10 / 100)
        assertThat(plan.upfrontCarbs + slowCarbs).isEqualTo(131)
    }

    @Test
    fun `degenerate fat-only plan with zero gram tails falls back to plain wizard`() {
        // 10 g fat at 1%/h x 8 h = 0.8 g -> truncates to 0 -> no measurable tail -> no plan
        assertThat(MealMacroPlan.compute(carbs = 0, fat = 10, protein = 0, params = params)).isNull()
        // 20 g fat = 1.6 -> 1 g tail -> plan exists
        assertThat(MealMacroPlan.compute(carbs = 0, fat = 20, protein = 0, params = params)).isNotNull()
    }

    @Test
    fun `frozen pizza 131C 47F 53P`() {
        val plan = MealMacroPlan.compute(carbs = 131, fat = 47, protein = 53, params = params)!!

        // intensity = (47/40 + 53/80) / 2 = 0.919
        assertThat(plan.intensity).isWithin(0.001).of(0.919)

        // upfront carbs = int(131 * (1 - 0.5 * 0.919)) = 70
        assertThat(plan.upfrontCarbs).isEqualTo(70)

        // upfront pct = 100 - 40 * 0.919 = 63
        assertThat(plan.suggestedUpfrontPercentage).isEqualTo(63)

        // primary tail = (131-70) + int(53*10/100) = 61 + 5 = 66
        assertThat(plan.primaryTailCarbs).isEqualTo(66)
        assertThat(plan.primaryTailShiftMin).isEqualTo(60)
        assertThat(plan.primaryTailDurationH).isEqualTo(4)

        // fat tail = int(47 * 1%/h * 8h) = 3
        assertThat(plan.fatTailCarbs).isEqualTo(3)
        assertThat(plan.fatTailShiftMin).isEqualTo(90)
        assertThat(plan.fatTailDurationH).isEqualTo(8)
    }

    @Test
    fun `two bigmacs 82C 50F 54P`() {
        val plan = MealMacroPlan.compute(carbs = 82, fat = 50, protein = 54, params = params)!!

        // intensity = (50/40 + 54/80) / 2 = 0.9625
        assertThat(plan.intensity).isWithin(0.001).of(0.9625)

        // upfront carbs = 82 * (1 - 0.5 * 0.96) = 42
        assertThat(plan.upfrontCarbs).isEqualTo(42)

        // upfront pct = 100 - 40 * 0.96 = 61
        assertThat(plan.suggestedUpfrontPercentage).isEqualTo(61)

        // primary tail = (82-42) + int(54*10/100) = 40 + 5 = 45
        assertThat(plan.primaryTailCarbs).isEqualTo(45)
        assertThat(plan.fatTailCarbs).isEqualTo(4) // 50 * 0.08 = 4.0
    }

    @Test
    fun `lean meal gets gentle plan`() {
        val plan = MealMacroPlan.compute(carbs = 60, fat = 5, protein = 10, params = params)!!

        // intensity = (5/40 + 10/80) / 2 = 0.125
        assertThat(plan.intensity).isWithin(0.001).of(0.125)

        // almost all carbs up front, pct near lean
        assertThat(plan.upfrontCarbs).isEqualTo(56)
        assertThat(plan.suggestedUpfrontPercentage).isEqualTo(95)
        assertThat(plan.primaryTailCarbs).isEqualTo(5) // 4 slow + int(10*0.1) = 1
    }

    @Test
    fun `intensity saturates at 1 for extreme meals`() {
        val intensity = params.intensity(fat = 200, protein = 400)
        assertThat(intensity).isEqualTo(1.0)

        val plan = MealMacroPlan.compute(carbs = 100, fat = 200, protein = 400, params = params)!!
        assertThat(plan.suggestedUpfrontPercentage).isEqualTo(60) // heavy pct
        assertThat(plan.upfrontCarbs).isEqualTo(50) // 50% slow share max
    }

    @Test
    fun `protein only meal shifts carbs but keeps lean percentage`() {
        // big protein, no fat: intensity = (0 + 60/80)/2 = 0.375
        val plan = MealMacroPlan.compute(carbs = 20, fat = 0, protein = 60, params = params)!!

        assertThat(plan.intensity).isWithin(0.001).of(0.375)
        assertThat(plan.fatTailCarbs).isEqualTo(0)
        assertThat(plan.suggestedUpfrontPercentage).isEqualTo(85)
        // primary tail = (20-16) + int(60*10/100) = 4 + 6 = 10
        assertThat(plan.primaryTailCarbs).isEqualTo(10)
    }

    @Test
    fun `zero carb meal still covers fat and protein via tails`() {
        val plan = MealMacroPlan.compute(carbs = 0, fat = 30, protein = 20, params = params)!!

        assertThat(plan.upfrontCarbs).isEqualTo(0)
        assertThat(plan.primaryTailCarbs).isEqualTo(2) // protein only
        assertThat(plan.fatTailCarbs).isEqualTo(2) // 30 * 0.08 = 2.4 -> 2
        assertThat(plan.hasTails).isTrue()
    }

    @Test
    fun `tails respect hard limits`() {
        val customParams = params.copy(
            proteinDurationH = 99, // above HardLimits.MAX_CARBS_DURATION_HOURS
            fatDurationH = 99
        )
        val plan = MealMacroPlan.compute(carbs = 100, fat = 50, protein = 50, params = customParams)!!
        assertThat(plan.primaryTailDurationH).isAtMost(10)
        assertThat(plan.fatTailDurationH).isAtMost(10)
    }

    @Test
    fun `heavy preference below lean preference is respected in clamping`() {
        val customParams = params.copy(upfrontPctLean = 80, upfrontPctHeavy = 50)
        val plan = MealMacroPlan.compute(carbs = 100, fat = 100, protein = 200, params = customParams)!!
        assertThat(plan.suggestedUpfrontPercentage).isAtLeast(50)
        assertThat(plan.suggestedUpfrontPercentage).isAtMost(80)
    }
}
