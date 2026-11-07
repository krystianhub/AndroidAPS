package app.aaps.implementation.stats

import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.resources.ResourceHelper

/** Derived I:C ratio (g per 1U) formatted for display, `n/a` if not calculable. */
fun TDD.icRatioText(rh: ResourceHelper): String =
    icRatio?.let { "%.1f".format(it) } ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
