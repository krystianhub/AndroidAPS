package app.aaps.ui.compose.overview.statusLights

import androidx.compose.runtime.Immutable
import app.aaps.core.ui.compose.StatusLevel

/**
 * UI state for Overview status section (sensor/insulin/cannula/battery)
 */
@Immutable
data class StatusUiState(
    val sensorStatus: StatusItem? = null,
    val insulinStatus: StatusItem? = null,
    val cannulaStatus: StatusItem? = null,
    val batteryStatus: StatusItem? = null,
    val showFill: Boolean = false,
    val showPumpBatteryChange: Boolean = false,
    val isPatchPump: Boolean = false,
    // MDI fork: the old always-visible pen status row
    val isMdi: Boolean = false,
    val lastBolusAge: String? = null,
    val lastBolusDose: String? = null,
    val lastBolusLevel: StatusLevel = StatusLevel.UNSPECIFIED,
    val lastBasalAge: String? = null,
    val lastBasalDose: String? = null,
    val lastBasalLevel: StatusLevel = StatusLevel.UNSPECIFIED,
    val insulinReq: String? = null,
    val insulinReqMedian: String? = null,
    val predictedBg: String? = null,
    val predictedBgLevel: StatusLevel = StatusLevel.UNSPECIFIED,
    /** The APS result behind the insulinReq/predicted values is older than 5 min. */
    val apsStale: Boolean = false
)