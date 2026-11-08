package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.data.configuration.Constants
import app.aaps.core.ui.compose.StatusLevel

/**
 * Pre-4.0 colour model for the predicted-BG light.
 *
 * - no eventual BG: no colour (the value is unavailable);
 * - eventual BG outside the fixed stats band (3.9–10.0 mmol/L): red;
 * - any prediction dipping below 3.9 mmol/L: red, even when the landing value is in range;
 * - eventual BG inside the profile target and no prediction below target low: green;
 * - everything else: orange.
 */
internal fun predictedBgLevel(
    eventualBgMgdl: Double?,
    lowestPredictedMgdl: Double?,
    targetLowMgdl: Double?,
    targetHighMgdl: Double?
): StatusLevel {
    val statsLow = Constants.STATS_RANGE_LOW_MMOL * Constants.MMOLL_TO_MGDL
    val statsHigh = Constants.STATS_RANGE_HIGH_MMOL * Constants.MMOLL_TO_MGDL
    return when {
        eventualBgMgdl == null -> StatusLevel.UNSPECIFIED
        eventualBgMgdl < statsLow || eventualBgMgdl > statsHigh -> StatusLevel.CRITICAL
        lowestPredictedMgdl != null && lowestPredictedMgdl < statsLow -> StatusLevel.CRITICAL
        targetLowMgdl != null && targetHighMgdl != null &&
            eventualBgMgdl in targetLowMgdl..targetHighMgdl &&
            (lowestPredictedMgdl == null || lowestPredictedMgdl >= targetLowMgdl) -> StatusLevel.NORMAL
        else -> StatusLevel.WARNING
    }
}
