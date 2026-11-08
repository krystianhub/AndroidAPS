package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.data.model.BS
import app.aaps.core.data.time.T
import app.aaps.core.ui.compose.StatusLevel

/**
 * Colour of the last-bolus status light from the insulin activity curve.
 *
 * Same thresholds as the pre-4.0 MDI status light: green near peak activity, orange while ramping
 * up or fading, neutral at onset, red once the dose has worn off.
 */
internal fun bolusStatusLevel(bolus: BS, now: Long): StatusLevel {
    if (bolus.amount <= 0) return StatusLevel.UNSPECIFIED
    val iCfg = bolus.iCfg
    // The bilinear activity curve peaks at the configured peak time.
    val peakTime = bolus.timestamp + T.mins(iCfg.peak.toLong()).msecs()
    val fMax = iCfg.iobCalcForTreatment(bolus, peakTime).activityContrib / bolus.amount
    val ratio = if (fMax > 0) iCfg.iobCalcForTreatment(bolus, now).activityContrib / bolus.amount / fMax else 0.0
    val afterPeak = now >= peakTime
    return when {
        ratio >= 0.8 -> StatusLevel.NORMAL
        ratio < 0.1  -> if (afterPeak) StatusLevel.CRITICAL else StatusLevel.UNSPECIFIED
        else         -> StatusLevel.WARNING
    }
}
