package app.aaps.plugins.main.general.overview.ui

import android.annotation.SuppressLint
import android.widget.TextView
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.WarnColors
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatusLightHandler @Inject constructor(
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val activePlugin: ActivePlugin,
    private val warnColors: WarnColors,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val tddCalculator: TddCalculator,
    private val decimalFormatter: DecimalFormatter
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * applies the extended statusLight subview on the overview fragment
     */
    fun updateStatusLights(
        cannulaAge: TextView?,
        cannulaUsage: TextView?,
        insulinAge: TextView?,
        reservoirLevel: TextView?,
        sensorAge: TextView?,
        sensorBatteryLevel: TextView?,
        batteryAge: TextView?,
        batteryLevel: TextView?
    ) {
        val pump = activePlugin.activePump
        val bgSource = activePlugin.activeBgSource
        handleAge(cannulaAge, TE.Type.CANNULA_CHANGE, IntKey.OverviewCageWarning, IntKey.OverviewCageCritical)
        handleAge(insulinAge, TE.Type.INSULIN_CHANGE, IntKey.OverviewIageWarning, IntKey.OverviewIageCritical)
        handleAge(sensorAge, TE.Type.SENSOR_CHANGE, IntKey.OverviewSageWarning, IntKey.OverviewSageCritical)
        if (pump.pumpDescription.isBatteryReplaceable || pump.isBatteryChangeLoggingEnabled()) {
            handleAge(batteryAge, TE.Type.PUMP_BATTERY_CHANGE, IntKey.OverviewBageWarning, IntKey.OverviewBageCritical)
        }

        val insulinUnit = rh.gs(app.aaps.core.ui.R.string.insulin_unit_shortname)
        if (cannulaUsage != null) scope.launch { handleUsage(cannulaUsage, insulinUnit) }
        if (pump.pumpDescription.isPatchPump) {
            handlePatchReservoirLevel(
                reservoirLevel,
                IntKey.OverviewResCritical, IntKey.OverviewResWarning,
                pump.reservoirLevel,
                insulinUnit,
                pump.pumpDescription.maxResorvoirReading.toDouble()
            )
        } else {
            handleLevel(reservoirLevel, IntKey.OverviewResCritical, IntKey.OverviewResWarning, pump.reservoirLevel, insulinUnit)
        }
        if (!config.AAPSCLIENT) {
            if (bgSource.sensorBatteryLevel != -1)
                handleLevel(sensorBatteryLevel, IntKey.OverviewSbatCritical, IntKey.OverviewSbatWarning, bgSource.sensorBatteryLevel.toDouble(), "%")
            else
                sensorBatteryLevel?.text = ""
        }

        if (!config.AAPSCLIENT) {
            // The Omnipod Eros does not report its battery level. However, some RileyLink alternatives do.
            // Depending on the user's configuration, we will either show the battery level reported by the RileyLink or "n/a"
            // Pump instance check is needed because at startup, the pump can still be VirtualPumpPlugin and that will cause a crash
            val erosBatteryLinkAvailable = pump.model() == PumpType.OMNIPOD_EROS && pump.isUseRileyLinkBatteryLevel()
            val batteryLevelValue  = pump.batteryLevel?.toDouble()
            if (batteryLevelValue != null && (pump.model().supportBatteryLevel || erosBatteryLinkAvailable)) {
                handleLevel(batteryLevel, IntKey.OverviewBattCritical, IntKey.OverviewBattWarning, batteryLevelValue, "%")
            } else {
                batteryLevel?.text = rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
                batteryLevel?.setTextColor(rh.gac(batteryLevel.context, app.aaps.core.ui.R.attr.defaultTextColor))
            }
        }
    }

    private fun handleAge(view: TextView?, type: TE.Type, warnSettings: IntKey, urgentSettings: IntKey) {
        val warn = preferences.get(warnSettings)
        val urgent = preferences.get(urgentSettings)
        val therapyEvent = persistenceLayer.getLastTherapyRecordUpToNow(type)
        if (therapyEvent != null) {
            warnColors.setColorByAge(view, therapyEvent, warn, urgent)
            view?.text = therapyEvent.age(rh.shortTextMode(), rh, dateUtil)
        } else {
            view?.text = if (rh.shortTextMode()) "-" else rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        }
    }

    /**
     * Shows time since the last bolus (hours and minutes). Intended for MDI (virtual pump),
     * where pump-specific status lights (battery, reservoir, cannula age) are meaningless.
     */
    fun updateLastBolusLight(view: TextView?) {
        view ?: return
        val lastBolus = persistenceLayer.getNewestBolusOfType(BS.Type.NORMAL)
        if (lastBolus != null) {
            val diff = dateUtil.computeDiff(lastBolus.timestamp, System.currentTimeMillis())
            val hours = diff[TimeUnit.HOURS]
            val minutes = diff[TimeUnit.MINUTES]
            view.text = "${hours}h ${String.format(Locale.ENGLISH, "%02d", minutes)}m"
            view.setTextColor(rh.gac(view.context, app.aaps.core.ui.R.attr.defaultTextColor))
        } else {
            view.text = if (rh.shortTextMode()) "-" else rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
            view.setTextColor(rh.gac(view.context, app.aaps.core.ui.R.attr.defaultTextColor))
        }
    }

    /**
     * Shows time since the last recorded basal (Lantus) injection, parsed from NOTE therapy
     * events ("Lantus xU ..."). Intended for MDI (virtual pump).
     */
    fun updateLastBasalLight(view: TextView?) {
        view ?: return
        val lastBasal = try {
            persistenceLayer.getTherapyEventDataFromTime(dateUtil.now() - T.days(7).msecs(), false)
                .blockingGet()
                .sortedByDescending { it.timestamp }
                .firstNotNullOfOrNull { te -> extractBasalDose(te.note)?.let { te } }
        } catch (e: Exception) {
            null
        }
        if (lastBasal != null) {
            val diff = dateUtil.computeDiff(lastBasal.timestamp, System.currentTimeMillis())
            val hours = diff[TimeUnit.HOURS] ?: 0L
            val minutes = diff[TimeUnit.MINUTES] ?: 0L
            view.text = "${hours}h ${String.format(Locale.ENGLISH, "%02d", minutes)}m"
            view.setTextColor(rh.gac(view.context, app.aaps.core.ui.R.attr.defaultTextColor))
        } else {
            view.text = if (rh.shortTextMode()) "-" else rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
            view.setTextColor(rh.gac(view.context, app.aaps.core.ui.R.attr.defaultTextColor))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleLevel(view: TextView?, criticalSetting: IntKey, warnSetting: IntKey, level: Double, units: String) {
        val resUrgent = preferences.get(criticalSetting)
        val resWarn = preferences.get(warnSetting)
        if (level > 0) view?.text = " " + decimalFormatter.to0Decimal(level, units)
        else view?.text = ""
        warnColors.setColorInverse(view, level, resWarn, resUrgent)
    }

    // Omnipod only reports reservoir level when it's 50 units or less, so we display "50+U" for any value > 50
    @Suppress("SameParameterValue")
    private fun handlePatchReservoirLevel(
        view: TextView?, criticalSetting: IntKey, warnSetting: IntKey, level: Double, units: String, maxReading: Double
    ) {
        if (level >= maxReading) {
            @Suppress("SetTextI18n")
            view?.text = "${decimalFormatter.to0Decimal(maxReading)}+ $units"
            view?.setTextColor(rh.gac(view.context, app.aaps.core.ui.R.attr.defaultTextColor))
        } else {
            handleLevel(view, criticalSetting, warnSetting, level, units)
        }
    }

    private suspend fun handleUsage(view: TextView?, units: String) {
        val therapyEvent = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        val usage =
            if (therapyEvent != null) {
                tddCalculator.calculateInterval(therapyEvent.timestamp, dateUtil.now(), allowMissingData = false)?.totalAmount ?: 0.0
            } else 0.0
        withContext(Dispatchers.Main) {
            view?.text = decimalFormatter.to0Decimal(usage, units)
        }
    }

    private fun TE.age(useShortText: Boolean, rh: ResourceHelper, dateUtil: DateUtil): String {
        val diff = dateUtil.computeDiff(timestamp, System.currentTimeMillis())
        var days = " " + rh.gs(app.aaps.core.interfaces.R.string.days) + " "
        var hours = " " + rh.gs(app.aaps.core.interfaces.R.string.hours) + " "
        if (useShortText) {
            days = rh.gs(app.aaps.core.interfaces.R.string.shortday)
            hours = rh.gs(app.aaps.core.interfaces.R.string.shorthour)
        }
        return diff[TimeUnit.DAYS].toString() + days + diff[TimeUnit.HOURS] + hours
    }

    private val BASAL_DOSE_REGEX = Regex("(?i)\\bLantus\\s*[:#]?\\s*([0-9]+(?:[.,][0-9]+)?)\\s*U\\b")

    private fun extractBasalDose(note: String?): Double? =
        note?.let { BASAL_DOSE_REGEX.find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }
}
