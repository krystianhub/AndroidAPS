package app.aaps.plugins.main.general.overview.ui

import android.annotation.SuppressLint
import android.widget.TextView
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
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
    private val profileFunction: ProfileFunction,
    private val warnColors: WarnColors,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val loop: Loop,
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
     *
     * Text color follows the pharmacokinetics of the *configured* insulin type (oref curve,
     * peak + DIA from the active insulin plugin and profile):
     * - activity near peak (>= 80% of max)  -> green (full effect)
     * - ramping up / fading (10-80%)        -> orange
     * - just injected, onset (< 10%)        -> neutral
     * - worn off, tail (< 10% after peak)   -> red
     */
    fun updateLastBolusLight(view: TextView?, valueView: TextView? = null) {
        view ?: return
        val lastBolus = persistenceLayer.getNewestBolusOfType(BS.Type.NORMAL)
        if (lastBolus != null && lastBolus.amount > 0) {
            val diff = dateUtil.computeDiff(lastBolus.timestamp, System.currentTimeMillis())
            // include DAYS - computeDiff decomposes, so 24h+1m would otherwise show as "0h 01m"
            val hours = (diff[TimeUnit.DAYS] ?: 0L) * 24 + (diff[TimeUnit.HOURS] ?: 0L)
            val minutes = diff[TimeUnit.MINUTES] ?: 0L
            view.text = "${hours}h ${String.format(Locale.ENGLISH, "%02d", minutes)}m"
            valueView?.text = formatDose(lastBolus.amount)
            view.setTextColor(rh.gac(view.context, bolusColorAttr(lastBolus, System.currentTimeMillis())))
        } else {
            view.text = if (rh.shortTextMode()) "-" else rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
            view.setTextColor(rh.gac(view.context, app.aaps.core.ui.R.attr.defaultTextColor))
            valueView?.text = rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        }
    }

    /** Formats a dose without trailing zeros: whole units as "8U", fractions with one decimal "8.5U". */
    private fun formatDose(amount: Double): String {
        val unit = rh.gs(app.aaps.core.ui.R.string.insulin_unit_shortname)
        return if (amount == Math.floor(amount) && !amount.isInfinite())
            "${amount.toLong()}$unit"
        else
            decimalFormatter.to1Decimal(amount, unit)
    }

    /** Normalized insulin activity fraction (0..~1) of [bolus] at [atTime], per the active insulin curve. */
    private fun bolusActivityFraction(bolus: BS, atTime: Long): Double {
        val insulin = activePlugin.activeInsulin
        val dia = profileFunction.getProfile()?.dia?.takeIf { it > 0 } ?: insulin.dia
        val result = insulin.iobCalcForTreatment(bolus, atTime, dia)
        return if (bolus.amount > 0) result.activityContrib / bolus.amount else 0.0
    }

    private fun bolusColorAttr(bolus: BS, now: Long): Int {
        val insulin = activePlugin.activeInsulin
        val peakTime = bolus.timestamp + T.mins(insulin.peak.toLong()).msecs()
        val fMax = bolusActivityFraction(bolus, peakTime)
        val ratio = if (fMax > 0) bolusActivityFraction(bolus, now) / fMax else 0.0
        val afterPeak = now >= peakTime
        return when {
            ratio >= 0.8   -> app.aaps.core.ui.R.attr.metadataTextOkColor       // full effect
            ratio < 0.1    -> if (afterPeak) app.aaps.core.ui.R.attr.urgentColor // worn off
                              else app.aaps.core.ui.R.attr.defaultTextColor      // just injected, onset
            else           -> app.aaps.core.ui.R.attr.metadataTextWarningColor  // ramping up / fading
        }
    }

    /**
     * Shows time since the last recorded basal (Lantus) injection, parsed from NOTE therapy
     * events ("Lantus xU ..."). Intended for MDI (virtual pump).
     *
     * Text color follows the Lantus action curve (~24h total):
     * - 0-2h   onset, barely working        -> neutral
     * - 2-4h   ramping up                   -> orange
     * - 4-18h  plateau, full effect         -> green
     * - 18-22h fading                       -> orange
     * - 22h+  worn off / overdue            -> red
     */
    fun updateLastBasalLight(view: TextView?, valueView: TextView? = null) {
        view ?: return
        val lastBasalDose = try {
            persistenceLayer.getTherapyEventDataFromTime(dateUtil.now() - T.days(7).msecs(), false)
                .blockingGet()
                .sortedByDescending { it.timestamp }
                .firstNotNullOfOrNull { te -> extractBasalDose(te.note)?.let { dose -> te to dose } }
        } catch (e: Exception) {
            null
        }
        if (lastBasalDose != null) {
            val (lastBasal, dose) = lastBasalDose
            val diff = dateUtil.computeDiff(lastBasal.timestamp, System.currentTimeMillis())
            // include DAYS - computeDiff decomposes, so 24h+1m would otherwise show as "0h 01m"
            val hours = (diff[TimeUnit.DAYS] ?: 0L) * 24 + (diff[TimeUnit.HOURS] ?: 0L)
            val minutes = diff[TimeUnit.MINUTES] ?: 0L
            view.text = "${hours}h ${String.format(Locale.ENGLISH, "%02d", minutes)}m"
            valueView?.text = formatDose(dose)
            val hoursSince = hours + minutes / 60.0
            view.setTextColor(
                rh.gac(
                    view.context, when {
                        hoursSince < 2.0  -> app.aaps.core.ui.R.attr.defaultTextColor      // onset, not yet active
                        hoursSince < 4.0  -> app.aaps.core.ui.R.attr.metadataTextWarningColor // ramping up
                        hoursSince < 18.0 -> app.aaps.core.ui.R.attr.metadataTextOkColor   // full effect
                        hoursSince < 22.0 -> app.aaps.core.ui.R.attr.metadataTextWarningColor // fading
                        else              -> app.aaps.core.ui.R.attr.urgentColor           // worn off / overdue
                    }
                )
            )
        } else {
            view.text = if (rh.shortTextMode()) "-" else rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
            view.setTextColor(rh.gac(view.context, app.aaps.core.ui.R.attr.defaultTextColor))
            valueView?.text = rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        }
    }

    /** Displays the latest APS insulinReq and a robust median of the previous 15 minutes, for information only. */
    fun updateInsulinReqLight(rawView: TextView?, medianView: TextView?) {
        if (rawView == null || medianView == null) return
        scope.launch {
            val now = dateUtil.now()
            val liveResult = loop.lastRun?.let { run ->
                val request = run.request
                val insulinReq = request?.insulinReq?.takeIf { it.isFinite() }
                if (now - run.lastAPSRun in 0..APS_INSULIN_REQ_WINDOW_MS) run.lastAPSRun to insulinReq else null
            }
            val recentValues = mutableMapOf<Long, Double>()
            var latestPersistedResult: Pair<Long, Double?>? = null
            try {
                val results = persistenceLayer.getApsResults(now - APS_INSULIN_REQ_WINDOW_MS, now)
                    .asSequence()
                    .filter { it.date in (now - APS_INSULIN_REQ_WINDOW_MS)..now }
                    .toList()
                latestPersistedResult = results.maxByOrNull { it.date }?.let { result -> result.date to result.insulinReq?.takeIf { it.isFinite() } }
                results.forEach { result -> result.insulinReq?.takeIf { it.isFinite() }?.let { recentValues[result.date] = it } }
            } catch (_: Exception) {
                // Keep the current in-memory result available even if history cannot be read.
            }
            liveResult?.let { (timestamp, insulinReq) ->
                latestPersistedResult?.first?.takeIf { timestamp - it in 0..60_000L }?.let(recentValues::remove)
                insulinReq?.let { recentValues[timestamp] = it }
            }
            val latestResult = listOfNotNull(latestPersistedResult, liveResult).maxByOrNull { it.first }?.second
            val median = median(recentValues.values.toList())
            val insulinUnit = rh.gs(app.aaps.core.ui.R.string.insulin_unit_shortname)
            val currentText = latestResult?.let { decimalFormatter.to2Decimal(it, insulinUnit) }
                ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
            val medianText = median?.let { decimalFormatter.to1Decimal(it, insulinUnit) }
                ?.let { "~$it" }
                ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
            withContext(Dispatchers.Main) {
                rawView.text = currentText
                medianView.text = medianText
            }
        }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private companion object {
        val APS_INSULIN_REQ_WINDOW_MS = T.mins(15).msecs()
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
