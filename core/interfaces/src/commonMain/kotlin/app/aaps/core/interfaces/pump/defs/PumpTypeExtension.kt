package app.aaps.core.interfaces.pump.defs

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.utils.Round
import kotlin.math.min

fun PumpType.baseBasalRange(): String =
    if (baseBasalMaxValue() == null) baseBasalMinValue().toString()
    else "${baseBasalMinValue()}-${baseBasalMaxValue()}"

fun PumpType.hasExtendedBasals(): Boolean = baseBasalSpecialSteps() != null || specialBolusSize() != null

fun PumpType.determineCorrectBolusSize(bolusAmount: Double): Double =
    Round.roundTo(bolusAmount, specialBolusSize()?.getStepSizeForAmount(bolusAmount) ?: bolusSize())

fun PumpType.determineCorrectBasalSize(basalAmount: Double): Double {
    // no temp basal settings defined for this pump type (e.g. MDI) -> no max dose clamp
    val maxDose = tbrSettings()?.maxDose ?: basalAmount
    return Round.roundTo(min(basalAmount, maxDose), baseBasalSpecialSteps()?.getStepSizeForAmount(basalAmount) ?: baseBasalStep())
}