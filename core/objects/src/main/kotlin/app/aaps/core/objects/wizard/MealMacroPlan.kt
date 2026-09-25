package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.utils.HardLimits

/**
 * Result of the meal macro computation: how a meal with carbs/fat/protein is split into
 * an upfront portion (bolused now through the wizard) and two extended-carb tails
 * (slowly absorbed via eCarbs).
 *
 * All values are grams / minutes / hours - unit conversion (grams -> insulin) is done
 * later by BolusWizard, which knows the profile IC.
 */
data class MealMacroPlan(
    /** Real carbs entered up front into the wizard (bolused now) */
    val upfrontCarbs: Int,
    /** Real carbs + protein equivalents, spread as eCarbs over the primary tail */
    val primaryTailCarbs: Int,
    /** Fat equivalents, spread as eCarbs over the fat tail */
    val fatTailCarbs: Int,
    /** Suggested upfront bolus percentage (applied via the wizard percentage mechanism) */
    val suggestedUpfrontPercentage: Int,
    /** Start delay of the primary (slow carbs + protein) tail in minutes */
    val primaryTailShiftMin: Int,
    /** Duration of the primary tail in hours */
    val primaryTailDurationH: Int,
    /** Start delay of the fat tail in minutes */
    val fatTailShiftMin: Int,
    /** Duration of the fat tail in hours */
    val fatTailDurationH: Int,
    /** Normalized meal "heaviness" 0.0 (lean) .. 1.0 (heavy), used for the adaptive percentage */
    val intensity: Double,
    /** Total fat and protein entered (kept for logging/preview) */
    val fat: Int,
    val protein: Int
) {
    val hasTails: Boolean
        get() = primaryTailCarbs > 0 || fatTailCarbs > 0

    companion object {
        /**
         * Computes the dosing plan for a meal.
         *
         * Conservative model (all factors configurable through [params]):
         *  - part of the real carbs is recognized as slow-absorbing (scaled by meal intensity) and moved
         *    to the primary tail together with protein carb-equivalents
         *  - fat grams are converted to carb-equivalents at params.fatPctPerHour per hour over the fat
         *    tail duration
         *  - the suggested upfront percentage interpolates between the lean and heavy preference
         *    based on the meal intensity (mean of normalized fat and protein content)
         *
         * Returns null when the meal macros are absent or below the activation thresholds - in
         * that case the wizard behaves exactly like upstream.
         */
        fun compute(
            carbs: Int,
            fat: Int,
            protein: Int,
            params: Params
        ): MealMacroPlan? {
            if (fat <= 0 && protein <= 0) return null
            // Safeguard: trace amounts of fat/protein would produce silly micro-plans
            // (99% upfront plus a 1 g tail) - below the thresholds fall back to plain wizard logic
            if (fat < params.minFatG && protein < params.minProteinG) return null

            val intensity = params.intensity(fat, protein)

            // Split of real carbs: lean meal -> everything up front, heavy meal -> up to slowShareMax moved to the tail
            val slowCarbShare = params.slowShareMax * intensity
            val upfrontCarbs = (carbs * (1.0 - slowCarbShare)).toInt().coerceAtLeast(0)
            val slowCarbs = (carbs - upfrontCarbs).coerceAtLeast(0)

            val primaryTailCarbs = slowCarbs + (protein * params.proteinPct / 100.0).toInt()
            val fatTailCarbs = (fat * params.fatPctPerHour / 100.0 * params.fatDurationH).toInt()

            // Degenerate plan (e.g. fat-only meal whose equivalents truncate to 0 g) -> plain wizard
            if (primaryTailCarbs == 0 && fatTailCarbs == 0) return null

            // Guard against a misconfigured heavy > lean: clamp heavy to lean instead of crashing on coerceIn
            val heavy = minOf(params.upfrontPctHeavy, params.upfrontPctLean)
            val suggestedUpfrontPercentage =
                (params.upfrontPctLean - (params.upfrontPctLean - heavy) * intensity)
                    .toInt()
                    .coerceIn(heavy, params.upfrontPctLean)

            return MealMacroPlan(
                upfrontCarbs = upfrontCarbs,
                primaryTailCarbs = primaryTailCarbs,
                fatTailCarbs = fatTailCarbs,
                suggestedUpfrontPercentage = suggestedUpfrontPercentage,
                primaryTailShiftMin = params.proteinShiftMin,
                primaryTailDurationH = params.proteinDurationH.coerceAtMost(HardLimits.MAX_CARBS_DURATION_HOURS.toInt()),
                fatTailShiftMin = params.fatShiftMin,
                fatTailDurationH = params.fatDurationH.coerceAtMost(HardLimits.MAX_CARBS_DURATION_HOURS.toInt()),
                intensity = intensity,
                fat = fat,
                protein = protein
            )
        }

        val DEFAULT_PARAMS = Params(
            proteinPct = 10,
            proteinShiftMin = 60,
            proteinDurationH = 4,
            fatPctPerHour = 1.0,
            fatShiftMin = 90,
            fatDurationH = 8,
            upfrontPctLean = 100,
            upfrontPctHeavy = 60,
            fatIntensityRef = 40,
            proteinIntensityRef = 80,
            minFatG = 5,
            minProteinG = 10
        )
    }

    /**
     * Tunable factors, sourced from preferences by the caller.
     *
     * @param proteinPct    part of protein grams added as carb equivalents to the primary tail (0-50)
     * @param proteinShiftMin start delay of the primary tail in minutes
     * @param proteinDurationH duration of the primary tail in hours (1-10, hard limited)
     * @param fatPctPerHour   fat grams converted to tail carbs per hour (0.0-5.0), e.g. 1.0 -> 30 g fat over 8 h = 2.4 g
     * @param fatShiftMin   start delay of the fat tail in minutes
     * @param fatDurationH    duration of the fat tail in hours (1-10, hard limited)
     * @param upfrontPctLean  suggested upfront percentage for a lean meal
     * @param upfrontPctHeavy suggested upfront percentage for a heavy meal
     * @param fatIntensityRef  fat grams considered a "heavy" meal (intensity 1.0 contribution)
     * @param proteinIntensityRef protein grams considered a "heavy" meal (intensity 1.0 contribution)
     * @param minFatG      minimum fat grams to activate the plan (below = plain wizard)
     * @param minProteinG   minimum protein grams to activate the plan (below = plain wizard)
     * @param slowShareMax   max part of real carbs moved to the tail for a heavy meal (0.0-1.0)
     */
    data class Params(
        val proteinPct: Int,
        val proteinShiftMin: Int,
        val proteinDurationH: Int,
        val fatPctPerHour: Double,
        val fatShiftMin: Int,
        val fatDurationH: Int,
        val upfrontPctLean: Int,
        val upfrontPctHeavy: Int,
        val fatIntensityRef: Int,
        val proteinIntensityRef: Int,
        val minFatG: Int = 5,
        val minProteinG: Int = 10,
        val slowShareMax: Double = 0.5
    ) {
        /**
         * Meal heaviness: mean of the normalized fat and protein content, clamped to 0..1.
         * Mean (not sum) so that only genuinely heavy meals saturate - a moderate fatty meal
         * keeps a proportionally gentler plan.
         */
        fun intensity(fat: Int, protein: Int): Double {
            val fatPart = if (fatIntensityRef > 0) fat.toDouble() / fatIntensityRef else 0.0
            val proteinPart = if (proteinIntensityRef > 0) protein.toDouble() / proteinIntensityRef else 0.0
            return ((fatPart + proteinPart) / 2.0).coerceIn(0.0, 1.0)
        }
    }
}
