package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.notifications.NotificationUserMessage
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusStepSize
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.wizard.InjectionPosition
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogInsulinBinding
import app.aaps.ui.extensions.toSignedString
import com.google.common.base.Joiner
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.text.DecimalFormat
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max

class InsulinDialog : DialogFragmentWithDate() {

    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var ctx: Context
    @Inject lateinit var config: Config
    @Inject lateinit var automation: Automation
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var hardLimits: HardLimits
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var loop: Loop

    private var queryingProtection = false
    private var lastPosition: Int? = null
    private var showPosition = false
    private var isMDI = false

    private val BASAL_DOSE_REGEX = Regex("(?i)\\bLantus\\s*[:#]?\\s*([0-9]+(?:[.,][0-9]+)?)\\s*U\\b")

    private val disposable = CompositeDisposable()
    private var _binding: DialogInsulinBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private val textWatcher: TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            _binding?.let {
                validateInputs()
            }
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
    }

    private fun validateInputs() {
        val maxInsulin = if (binding.recordBasalInsulin.isChecked) hardLimits.maxBolus() else constraintChecker.getMaxBolusAllowed().value()
        if (abs(binding.time.value.toInt()) > 12 * 60) {
            binding.time.value = 0.0
            ToastUtils.warnToast(context, app.aaps.core.ui.R.string.constraint_applied)
        }
        if (binding.amount.value > maxInsulin) {
            binding.amount.value = 0.0
            ToastUtils.warnToast(context, R.string.bolus_constraint_applied)
        }
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putDouble("time", binding.time.value)
        savedInstanceState.putDouble("amount", binding.amount.value)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        onCreateViewGeneral()
        _binding = DialogInsulinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pump = activePlugin.activePump
        isMDI = pump.isMDI()
        val recordOnlyForced = config.AAPSCLIENT || loop.runningMode.isPumpSuspended() || !pump.isInitialized()
        val maxInsulin = constraintChecker.getMaxBolusAllowed().value()

        if (recordOnlyForced) {
            binding.recordOnly.isChecked = true
            binding.recordOnly.isEnabled = false
        }

        if (isMDI) {
            // MDI: there is no pump to deliver through - every bolus is record-only, so the checkbox is redundant
            binding.recordOnly.isChecked = true
            binding.recordOnly.visibility = View.GONE
        }

        if (loop.runningMode.isPumpSuspended() || !pump.isInitialized()) {
            binding.recordOnly.setTextColor(rh.gac(app.aaps.core.ui.R.attr.warningColor))
            binding.header.setBackgroundColor(rh.gac(app.aaps.core.ui.R.attr.ribbonWarningColor))
            binding.headerText.setTextColor(rh.gac(app.aaps.core.ui.R.attr.ribbonTextWarningColor))
        }

        binding.time.setParams(
            savedInstanceState?.getDouble("time")
                ?: 0.0, -12 * 60.0, 12 * 60.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )
        binding.amount.setParams(
            savedInstanceState?.getDouble("amount")
                ?: 0.0, 0.0, maxInsulin, activePlugin.activePump.pumpDescription.bolusStep, decimalFormatter.pumpSupportedBolusFormat(activePlugin.activePump.pumpDescription.bolusStep),
            false, binding.okcancel.ok, textWatcher
        )

        val plus05Text = preferences.get(DoubleKey.OverviewInsulinButtonIncrement1).toSignedString(activePlugin.activePump, decimalFormatter)
        binding.plus05.text = plus05Text
        binding.plus05.contentDescription = rh.gs(app.aaps.core.ui.R.string.overview_insulin_label) + " " + plus05Text
        binding.plus05.setOnClickListener {
            binding.amount.value = max(0.0, binding.amount.value + preferences.get(DoubleKey.OverviewInsulinButtonIncrement1))
            validateInputs()
            binding.amount.announceValue()
        }
        val plus10Text = preferences.get(DoubleKey.OverviewInsulinButtonIncrement2).toSignedString(activePlugin.activePump, decimalFormatter)
        binding.plus10.text = plus10Text
        binding.plus10.contentDescription = rh.gs(app.aaps.core.ui.R.string.overview_insulin_label) + " " + plus10Text
        binding.plus10.setOnClickListener {
            binding.amount.value = max(0.0, binding.amount.value + preferences.get(DoubleKey.OverviewInsulinButtonIncrement2))
            validateInputs()
            binding.amount.announceValue()
        }
        val plus20Text = preferences.get(DoubleKey.OverviewInsulinButtonIncrement3).toSignedString(activePlugin.activePump, decimalFormatter)
        binding.plus20.text = plus20Text
        binding.plus20.contentDescription = rh.gs(app.aaps.core.ui.R.string.overview_insulin_label) + " " + plus20Text
        binding.plus20.setOnClickListener {
            binding.amount.value = max(0.0, binding.amount.value + preferences.get(DoubleKey.OverviewInsulinButtonIncrement3))
            validateInputs()
            binding.amount.announceValue()
        }

        if (!binding.recordOnly.isChecked) {
            binding.timeLayout.visibility = View.GONE
        }
        binding.recordOnly.setOnCheckedChangeListener { _, isChecked: Boolean ->
            binding.timeLayout.visibility = isChecked.toVisibility()
        }
        binding.insulinLabel.labelFor = binding.amount.editTextId
        binding.timeLabel.labelFor = binding.time.editTextId

        binding.positionLayout.root.visibility =
            (preferences.get(BooleanKey.OverviewShowPositionInDialogs) && activePlugin.activePump.isMDI()).toVisibility()
        showPosition = binding.positionLayout.root.visibility == View.VISIBLE
        if (showPosition) {
            lastPosition = InjectionPosition.findLastPosition(
                persistenceLayer.getBolusesFromTimeToTime(dateUtil.now() - T.days(3).msecs(), dateUtil.now(), false)
            )
            binding.positionLayout.lastPosition.text = lastPosition?.let { "pos $it" } ?: ""
            InjectionPosition.suggestNext(lastPosition)?.let { binding.positionLayout.position.setText(it.toString()) }
        }

        // Basal (long-acting) insulin recording - MDI only
        binding.recordBasalInsulin.visibility = activePlugin.activePump.isMDI().toVisibility()
        if (!activePlugin.activePump.isMDI()) binding.recordBasalInsulin.isChecked = false
        // MDI: eating-soon TT from the insulin dialog is useless (no pump to enact anything with)
        binding.startEatingSoonTt.visibility = (!activePlugin.activePump.isMDI()).toVisibility()
        if (activePlugin.activePump.isMDI()) binding.startEatingSoonTt.isChecked = false
        binding.recordBasalInsulin.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.recordOnly.isChecked = true
                binding.recordOnly.isEnabled = false
                binding.startEatingSoonTt.isChecked = false
                binding.startEatingSoonTt.isEnabled = false
                // prefill with the last recorded basal (Lantus) dose
                val lastBasalDose = findLastBasalDose()
                binding.amount.setParams(
                    lastBasalDose ?: binding.amount.value, 0.0, hardLimits.maxBolus(),
                    activePlugin.activePump.pumpDescription.bolusStep,
                    decimalFormatter.pumpSupportedBolusFormat(activePlugin.activePump.pumpDescription.bolusStep),
                    false, binding.okcancel.ok, textWatcher
                )
                if (lastBasalDose != null) binding.amount.value = lastBasalDose
            } else {
                binding.recordOnly.isEnabled = !recordOnlyForced
                binding.startEatingSoonTt.isEnabled = true
                binding.amount.setParams(
                    binding.amount.value, 0.0, maxInsulin,
                    activePlugin.activePump.pumpDescription.bolusStep,
                    decimalFormatter.pumpSupportedBolusFormat(activePlugin.activePump.pumpDescription.bolusStep),
                    false, binding.okcancel.ok, textWatcher
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
        _binding = null
    }

    override fun submit(): Boolean {
        if (_binding == null) return false
        val pumpDescription = activePlugin.activePump.pumpDescription
        val insulin = SafeParse.stringToDouble(binding.amount.text)
        val recordBasalChecked = binding.recordBasalInsulin.isChecked
        // basal insulin is record-only, bolus delivery constraints do not apply
        val insulinAfterConstraints =
            if (recordBasalChecked) insulin
            else constraintChecker.applyBolusConstraints(ConstraintObject(insulin, aapsLogger)).value()
        val actions: LinkedList<String?> = LinkedList()
        val units = profileFunction.getUnits()
        val unitLabel = if (units == GlucoseUnit.MMOL) rh.gs(app.aaps.core.ui.R.string.mmol) else rh.gs(app.aaps.core.ui.R.string.mgdl)
        val recordOnlyChecked = binding.recordOnly.isChecked
        val eatingSoonChecked = binding.startEatingSoonTt.isChecked
        val previousBasalDose =
            if (recordBasalChecked) findLastBasalDose() ?: profileFunction.getProfile()?.baseBasalSum()
            else null

        if (insulinAfterConstraints > 0) {
            if (recordBasalChecked) {
                actions.add(
                    rh.gs(R.string.record_basal_insulin) + ": " + decimalFormatter.toPumpSupportedBolus(insulinAfterConstraints, activePlugin.activePump.pumpDescription.bolusStep)
                        .formatColor(context, rh, app.aaps.core.ui.R.attr.bolusColor)
                )
                previousBasalDose?.let { previousDose ->
                    if (!basalMatchesProfile(insulinAfterConstraints, previousDose))
                        actions.add(
                            rh.gs(
                                R.string.basal_insulin_profile_updated, insulinAfterConstraints, previousDose,
                                insulinAfterConstraints,
                                max(Round.roundTo(insulinAfterConstraints / 24.0, 0.01), 0.01)
                            ).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor)
                        )
                }
            } else {
                actions.add(
                    rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + decimalFormatter.toPumpSupportedBolus(insulinAfterConstraints, activePlugin.activePump.pumpDescription.bolusStep)
                        .formatColor(context, rh, app.aaps.core.ui.R.attr.bolusColor)
                )
                if (recordOnlyChecked && !isMDI)
                    actions.add(rh.gs(app.aaps.core.ui.R.string.bolus_recorded_only).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))
                if (abs(insulinAfterConstraints - insulin) > pumpDescription.pumpType.determineCorrectBolusStepSize(insulinAfterConstraints))
                    actions.add(
                        rh.gs(app.aaps.core.ui.R.string.bolus_constraint_applied_warn, insulin, insulinAfterConstraints).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor)
                    )
            }
        }
        val eatingSoonTTDuration = preferences.get(IntKey.OverviewEatingSoonDuration)
        val eatingSoonTT = preferences.get(UnitDoubleKey.OverviewEatingSoonTarget)
        if (eatingSoonChecked)
            actions.add(
                rh.gs(R.string.temp_target_short) + ": " + (decimalFormatter.to1Decimal(eatingSoonTT) + " " + unitLabel + " (" + rh.gs(
                    app.aaps.core.ui.R.string.format_mins,
                    eatingSoonTTDuration
                ) + ")")
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.tempTargetConfirmation)
            )

        val timeOffset = binding.time.value.toInt()
        val time = dateUtil.now() + T.mins(timeOffset.toLong()).msecs()
        if (timeOffset != 0)
            actions.add(rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.dateAndTimeString(time))

        var notes = binding.notesLayout.notes.text.toString()
        if (showPosition) {
            SafeParse.stringToInt(binding.positionLayout.position.text.toString())?.let { position ->
                if (position in 1..InjectionPosition.MAX_POSITION) {
                    notes = InjectionPosition.appendToNotes(notes, position)
                    actions.add(rh.gs(app.aaps.core.ui.R.string.position_label) + ": " + position)
                }
            }
        }
        if (notes.isNotEmpty())
            actions.add(rh.gs(app.aaps.core.ui.R.string.notes_label) + ": " + notes)

        if (insulinAfterConstraints > 0 || eatingSoonChecked) {
            activity?.let { activity ->
                OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.bolus), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                    if (eatingSoonChecked) {
                        disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                            TT(
                                timestamp = System.currentTimeMillis(),
                                duration = TimeUnit.MINUTES.toMillis(eatingSoonTTDuration.toLong()),
                                reason = TT.Reason.EATING_SOON,
                                lowTarget = profileUtil.convertToMgdl(eatingSoonTT, profileFunction.getUnits()),
                                highTarget = profileUtil.convertToMgdl(eatingSoonTT, profileFunction.getUnits())
                            ),
                            action = Action.TT, source = Sources.InsulinDialog,
                            note = notes,
                            listValues = listOf(
                                ValueWithUnit.TETTReason(TT.Reason.EATING_SOON),
                                ValueWithUnit.fromGlucoseUnit(eatingSoonTT, units),
                                ValueWithUnit.Minute(eatingSoonTTDuration)
                            )
                        ).subscribe()
                    }
                    if (insulinAfterConstraints > 0) {
                        if (recordBasalChecked) {
                            recordBasalInsulin(insulinAfterConstraints, notes, time, previousBasalDose)
                        } else {
                            val detailedBolusInfo = DetailedBolusInfo()
                            detailedBolusInfo.eventType = TE.Type.CORRECTION_BOLUS
                            detailedBolusInfo.insulin = insulinAfterConstraints
                            detailedBolusInfo.context = context
                            detailedBolusInfo.notes = notes
                            detailedBolusInfo.timestamp = time
                            if (recordOnlyChecked) {
                            disposable += persistenceLayer.insertOrUpdateBolus(
                                bolus = detailedBolusInfo.createBolus(),
                                action = Action.BOLUS,
                                source = Sources.InsulinDialog,
                                note = rh.gs(app.aaps.core.ui.R.string.record) + if (notes.isNotEmpty()) ": $notes" else ""
                            ).subscribe()
                            if (timeOffset == 0)
                                automation.removeAutomationEventBolusReminder()
                        } else {
                            uel.log(
                                Action.BOLUS, Sources.InsulinDialog,
                                notes,
                                ValueWithUnit.Insulin(insulinAfterConstraints)
                            )
                            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                                override fun run() {
                                    if (!result.success) {
                                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                                    } else {
                                        automation.removeAutomationEventBolusReminder()
                                    }
                                }
                            })
                        }
                        }
                    }
                })
            }
        } else
            activity?.let { activity ->
                OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.bolus), rh.gs(app.aaps.core.ui.R.string.no_action_selected))
            }
        return true
    }

    private fun basalMatchesProfile(amount: Double, previousDose: Double): Boolean =
        abs(amount - previousDose) <= max(1.0, previousDose * 0.1)

    private fun findLastBasalDose(): Double? =
        try {
            persistenceLayer.getTherapyEventDataFromTime(dateUtil.now() - T.days(7).msecs(), false)
                .blockingGet()
                .sortedByDescending { it.timestamp }
                .firstNotNullOfOrNull { extractBasalDose(it.note) }
        } catch (e: Exception) {
            null
        }

    private fun extractBasalDose(note: String?): Double? =
        note?.let { BASAL_DOSE_REGEX.find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }

    private fun recordBasalInsulin(amount: Double, notes: String, time: Long, previousDose: Double?) {
        val doseText = decimalFormatter.toPumpSupportedBolus(amount, activePlugin.activePump.pumpDescription.bolusStep)
        val basalNotes = "Lantus " + doseText + "U" + if (notes.isNotEmpty()) " $notes" else ""
        disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = time,
                type = TE.Type.NOTE,
                note = basalNotes,
                glucoseUnit = profileFunction.getUnits()
            ),
            timestamp = time,
            action = Action.CAREPORTAL,
            source = Sources.InsulinDialog,
            note = basalNotes,
            listValues = listOf(
                ValueWithUnit.TEType(TE.Type.NOTE),
                ValueWithUnit.Insulin(amount)
            )
        ).subscribe()
        updateBasalProfileIfNeeded(amount, previousDose)
    }

    private fun updateBasalProfileIfNeeded(amount: Double, previousDose: Double?) {
        previousDose ?: return
        if (basalMatchesProfile(amount, previousDose)) return

        val profileSource = activePlugin.activeProfileSource
        val profileStore = profileSource.profile ?: return
        val profileList = profileStore.getProfileList()
        val index = profileList.indexOf(profileFunction.getOriginalProfileName())
        // only touch a local profile we can positively identify
        val singleProfile = if (index >= 0) {
            profileSource.currentProfileIndex = index
            profileSource.currentProfile()
        } else null
        if (singleProfile == null) {
            rxBus.send(
                EventNewNotification(
                    NotificationUserMessage(rh.gs(R.string.basal_insulin_profile_update_failed, amount, previousDose), Notification.NORMAL)
                )
            )
            return
        }
        val flatRate = max(Round.roundTo(amount / 24.0, 0.01), 0.01)
        singleProfile.basal = JSONArray().put(JSONObject().put("time", "00:00").put("timeAsSeconds", 0).put("value", flatRate))
        profileSource.storeSettings(timestamp = dateUtil.now())
        val newStore = profileSource.profile ?: return
        if (profileFunction.createProfileSwitch(
                profileStore = newStore,
                profileName = singleProfile.name,
                durationInMinutes = 0,
                percentage = 100,
                timeShiftInHours = 0,
                timestamp = dateUtil.now(),
                action = Action.PROFILE_SWITCH,
                source = Sources.InsulinDialog,
                note = rh.gs(R.string.record_basal_insulin),
                listValues = listOf(ValueWithUnit.SimpleString(singleProfile.name))
            )
        )
            rxBus.send(
                EventNewNotification(
                    NotificationUserMessage(
                        rh.gs(R.string.basal_insulin_profile_updated, amount, previousDose, amount, flatRate),
                        Notification.NORMAL
                    )
                )
            )
    }

    override fun onResume() {
        super.onResume()
        if (!queryingProtection) {
            queryingProtection = true
            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    ToastUtils.warnToast(ctx, R.string.dialog_canceled)
                    dismiss()
                }
                protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, { queryingProtection = false }, cancelFail, cancelFail)
            }
        }
    }
}