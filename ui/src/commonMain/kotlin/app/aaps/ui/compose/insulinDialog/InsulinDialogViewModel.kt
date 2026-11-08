package app.aaps.ui.compose.insulinDialog

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.data.Block
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.data.ui.ConfirmationRole
import app.aaps.core.data.ui.confirmationLines
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.clientcontrol.isNotDeliveryError
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.insulin.InsulinManager
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.pump.BasalInjection
import app.aaps.core.interfaces.pump.InjectionPosition
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.SmbPause
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.tempTargets.ttDurationMinutes
import app.aaps.core.interfaces.tempTargets.ttTargetMgdl
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.runningMode.PumpCommandGate
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.clientcontrol.failText
import app.aaps.ui.UiStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// Registers itself: @ViewModelKey infers the key from the class. No graph entry, and deliberately
// unscoped so each screen gets its own.
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class InsulinDialogViewModel(
    constraintChecker: ConstraintsChecker,
    private val profileFunction: ProfileFunction,
    private val profileRepository: ProfileRepository,
    private val profileUtil: ProfileUtil,
    private val activePlugin: ActivePlugin,
    private val ch: ConcentrationHelper,
    val insulinManager: InsulinManager,
    val config: Config,
    private val automation: Automation,
    val decimalFormatter: DecimalFormatter,
    private val loop: Loop,
    val preferences: Preferences,
    val rh: TextResolver,
    val dateUtil: DateUtil,
    hardLimits: HardLimits,
    private val batchExecutor: BatchExecutor,
    private val persistenceLayer: PersistenceLayer,
    private val uel: UserEntryLogger,
    private val rxBus: RxBus,
    // Unqualified: @ApplicationScope is a javax qualifier and cannot appear in commonMain. The graph
    // binds the same instance under both names.
    private val appScope: CoroutineScope,
    nsClient: NsClient,
    commandQueue: CommandQueue
) : ViewModel() {

    private val _uiState = MutableStateFlow(InsulinDialogUiState())
    val uiState: StateFlow<InsulinDialogUiState> = _uiState.asStateFlow()

    sealed class SideEffect {
        data class ShowDeliveryError(val comment: String) : SideEffect()
        data object ShowNoActionDialog : SideEffect()
        data object NavigateBack : SideEffect()

        /**
         * The MASTER prepared the batch and returned its merged confirmation [lines]; show them, then [commit] [bolusId],
         * or [discard] it on Cancel. Close it at [closeAt]: the master's SMB pause for the dose has run out by then.
         */
        data class ShowConfirmation(val bolusId: Long, val lines: List<ConfirmationLine>, val closeAt: TimeMark) : SideEffect()

        /** A basal (Lantus) injection has no pump batch to prepare; show its own summary, then record it. */
        data class ShowBasalConfirmation(val state: InsulinDialogUiState, val lines: List<ConfirmationLine>) : SideEffect()
    }

    private val _sideEffect = MutableSharedFlow<SideEffect>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sideEffect: SharedFlow<SideEffect> = _sideEffect.asSharedFlow()

    init {
        val now = dateUtil.now()
        val pump = activePlugin.activePump
        val constrainedMax = constraintChecker.getMaxBolusAllowed().value()
        val maxInsulin = if (constrainedMax > 0.0) constrainedMax else hardLimits.maxBolus()
        val bolusStep = pump.pumpDescription.bolusStep
        val units = profileFunction.getUnits()

        // Conservative default for medical dosing UI: start in record-only mode and relax
        // only after the async loop.runningMode() check below confirms the loop is actually
        // running. Erring on "real delivery" first and flipping to "record-only" later would
        // (a) visibly toggle the checkbox under the user, and (b) let a fast-tapping user
        // confirm a "deliver" dialog that the Loop guard would then reject. Safer to start
        // checked and uncheck once we know the loop is running.
        val pumpInitialized = pump.isInitialized()
        val isAapsClient = config.AAPSCLIENT
        val initialForcedRecordOnly = true

        _uiState.update {
            InsulinDialogUiState(
                insulin = 0.0,
                timeOffsetMinutes = 0,
                eatingSoonTtChecked = false,
                // A temp target needs a higher role than insulin; the master would refuse the bolus together with it.
                canSetTempTarget = nsClient.clientRole.value.allows(ElementType.TEMP_TARGET_MANAGEMENT.role),
                recordOnlyChecked = initialForcedRecordOnly,
                notes = "",
                eventTime = now,
                eventTimeOriginal = now,
                insulins = insulinManager.insulins.toList(),
                maxInsulin = maxInsulin,
                bolusStep = bolusStep,
                insulinButtonIncrement1 = preferences.get(DoubleKey.OverviewInsulinButtonIncrement1),
                insulinButtonIncrement2 = preferences.get(DoubleKey.OverviewInsulinButtonIncrement2),
                insulinButtonIncrement3 = preferences.get(DoubleKey.OverviewInsulinButtonIncrement3),
                eatingSoonTtTarget = profileUtil.fromMgdlToUnits(preferences.ttTargetMgdl(TT.Reason.EATING_SOON), units),
                eatingSoonTtDuration = preferences.ttDurationMinutes(TT.Reason.EATING_SOON),
                units = units,
                showNotesFromPreferences = preferences.get(BooleanKey.OverviewShowNotesInDialogs),
                simpleMode = preferences.get(BooleanKey.GeneralSimpleMode),
                isAapsClient = isAapsClient,
                forcedRecordOnly = initialForcedRecordOnly,
                isMdi = pump.isMDI(),
                maxBasalDose = preferences.get(DoubleKey.MdiMaxBasalInjection),
                showPosition = pump.isMDI() && preferences.get(BooleanKey.OverviewShowPositionInDialogs)
            )
        }
        if (pump.isMDI() && preferences.get(BooleanKey.OverviewShowPositionInDialogs)) viewModelScope.launch {
            val last = lastInjectionPosition()
            _uiState.update {
                it.copy(
                    lastPosition = last,
                    position = InjectionPosition.suggestNext(last) ?: 1
                )
            }
        }
        if (pump.isMDI()) viewModelScope.launch {
            val last = findLastBasalDose()
            _uiState.update {
                it.copy(
                    lastBasalDose = last,
                    // never overwrite an amount the user already typed with the previous dose
                    insulin = if (it.insulin == 0.0 && it.recordBasal && last != null) last else it.insulin
                )
            }
        }
        viewModelScope.launch {
            val runningIcfg = getRunningIcfg()
            val mode = loop.runningMode()
            val cantDeliverBolus = PumpCommandGate.check(mode, PumpCommandGate.CommandKind.BOLUS) is PumpCommandGate.Decision.Reject
            // A client delivers via the master (the batch round-trip), so its local pump state must NOT force
            // record-only; only a master's own can't-deliver conditions do. MDI is always record-only: there is
            // no pump to deliver a rapid-acting bolus.
            val forcedRecordOnly = if (isAapsClient) false else (cantDeliverBolus || !pumpInitialized || pump.isMDI())
            _uiState.update {
                it.copy(
                    selectedIcfg = runningIcfg,
                    forcedRecordOnly = forcedRecordOnly,
                    recordOnlyChecked = forcedRecordOnly
                )
            }
        }
        commandQueue.smbBeingDelivered
            .onEach { delivering -> _uiState.update { it.copy(smbBeingDelivered = delivering) } }
            .launchIn(viewModelScope)
    }

    private suspend fun getRunningIcfg() = profileFunction.getRunningOrRequestedICfg()

    fun refreshInsulinButtons() {
        _uiState.update {
            it.copy(
                insulinButtonIncrement1 = preferences.get(DoubleKey.OverviewInsulinButtonIncrement1),
                insulinButtonIncrement2 = preferences.get(DoubleKey.OverviewInsulinButtonIncrement2),
                insulinButtonIncrement3 = preferences.get(DoubleKey.OverviewInsulinButtonIncrement3)
            )
        }
    }

    fun updateInsulin(value: Double) {
        val state = uiState.value
        // A Lantus pen cannot be dialled in half units; snap typed input to its whole-unit grid.
        val stepped = if (state.recordBasal) BasalInjection.snapDose(value) else value
        _uiState.update { it.copy(insulin = stepped.coerceIn(0.0, it.maxAmount)) }
    }

    /** MDI: switch the dialog between a rapid-acting bolus and a long-acting (Lantus) injection. */
    fun updateRecordBasal(checked: Boolean) {
        _uiState.update {
            it.copy(
                recordBasal = checked,
                recordOnlyChecked = if (checked) true else it.recordOnlyChecked,
                eatingSoonTtChecked = if (checked) false else it.eatingSoonTtChecked,
                // Leaving basal mode must not carry a Lantus-sized amount into the (uncapped) rapid-acting field.
                insulin = if (checked) BasalInjection.snapDose(it.lastBasalDose ?: 0.0).coerceAtMost(it.maxBasalDose) else 0.0
            )
        }
    }

    fun addInsulin(increment: Double) {
        val state = uiState.value
        // Snap to the deliverable bolus step so a preset increment that isn't a step multiple (e.g. a 0.25 U
        // button on U200, step 0.10) lands on a deliverable value. ch.bolusStep is the step of the pump, converted
        // by the concentration; asked at the target, in case a pump steps by amount. Floor, never up.
        // A record-only dose (a pen) never reaches the pump, so it is not put on the pump's grid.
        val target = max(0.0, state.insulin + increment).coerceAtMost(state.maxAmount)
        val newValue = when {
            state.recordBasal      -> BasalInjection.snapDose(target).coerceAtMost(state.maxAmount)
            state.recordOnlyChecked -> target
            else                   -> Round.floorTo(target, ch.bolusStep(target))
        }
        _uiState.update { it.copy(insulin = newValue) }
    }

    fun updateTimeOffset(minutes: Int) {
        val clamped = minutes.coerceIn(-12 * 60, 12 * 60)
        val state = uiState.value
        val newEventTime = state.eventTimeOriginal + clamped.toLong() * 60 * 1000
        _uiState.update {
            it.copy(
                timeOffsetMinutes = clamped,
                eventTime = newEventTime
            )
        }
    }

    fun updateEatingSoonTt(checked: Boolean) {
        _uiState.update { it.copy(eatingSoonTtChecked = checked) }
    }

    fun updateRecordOnly(checked: Boolean) {
        _uiState.update { it.copy(recordOnlyChecked = checked) }
        if (!checked)
            viewModelScope.launch {
                val runningIcfg = getRunningIcfg()
                _uiState.update { it.copy(selectedIcfg = runningIcfg) }
            }
    }

    fun selectInsulinType(iCfg: ICfg) {
        _uiState.update { it.copy(selectedIcfg = iCfg) }
    }

    fun updateNotes(value: String) {
        _uiState.update { it.copy(notes = value) }
    }

    fun updatePosition(value: Int) {
        _uiState.update { it.copy(position = value.coerceIn(1, InjectionPosition.MAX_POSITION)) }
    }

    private fun notesWithPosition(state: InsulinDialogUiState): String =
        if (state.showPosition) InjectionPosition.appendToNotes(state.notes, state.position) else state.notes

    fun updateEventTime(timeMillis: Long) {
        val state = uiState.value
        val newOffset = ((timeMillis - state.eventTimeOriginal) / (1000 * 60)).toInt()
        _uiState.update {
            it.copy(
                eventTime = timeMillis,
                timeOffsetMinutes = newOffset
            )
        }
    }

    @Volatile private var confirmedState: InsulinDialogUiState? = null

    /**
     * Tap-confirm → ask the MASTER to PREPARE the batch (cap + build the merged confirmation). Client = signed
     * round-trip; master = local. The user then confirms the MASTER's exact lines (the contract — the client never
     * builds or caps its own numbers) and [commit] delivers. appScope: the screen may navigate away (cancelling
     * viewModelScope) while the round-trip is in flight.
     */
    fun prepareAndConfirm() {
        // Counted from before the prepare, so on a client the confirmation closes before the master's pause ends.
        val closeAt = TimeSource.Monotonic.markNow() + SmbPause.MAX_DURATION
        appScope.launch {
            val state = uiState.value
            if (state.recordBasal) {
                val basalState = state.copy(insulin = BasalInjection.snapDose(state.insulin))
                val max = preferences.get(DoubleKey.MdiMaxBasalInjection)
                when {
                    basalState.insulin > max -> rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(UiStrings.basal_insulin_above_max, max)))
                    basalState.insulin <= 0.0 -> _sideEffect.tryEmit(SideEffect.ShowNoActionDialog)
                    else                      -> _sideEffect.tryEmit(SideEffect.ShowBasalConfirmation(basalState, basalConfirmationLines(basalState)))
                }
                return@launch
            }
            confirmedState = state
            val actions = buildActions(state)
            if (actions.isEmpty()) {
                _sideEffect.tryEmit(SideEffect.ShowNoActionDialog)
                return@launch
            }
            when (val prepared = batchExecutor.prepare(actions, Sources.InsulinDialog, rh.gs(InterfacesStrings.bolus))) {
                is ActionProgress.Prepared -> _sideEffect.tryEmit(SideEffect.ShowConfirmation(prepared.id, prepared.lines, closeAt))
                // Offline block (and a master-local failure) surface here. A client's round-trip failure already showed
                // on the app-level modal, with the master's own text, so a client re-surfaces only NotReachable.
                is ActionProgress.Rejected -> when {
                    // No-op after caps (e.g. the bolus was constraint-capped to 0): neutral message, NOT the bolus-error alarm.
                    prepared.reason == FailureReason.NoAction -> _sideEffect.tryEmit(SideEffect.ShowNoActionDialog)
                    prepared.reason.isNotDeliveryError()      -> rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(prepared.reason.failText())))
                    config.AAPSCLIENT                         -> Unit
                    // Nothing was sent to the pump and the dialog keeps its values: a plain message, not the bolus-error alarm.
                    else                                      -> rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = prepared.detail ?: rh.gs(prepared.reason.failText())))
                }

                else                       -> Unit // Unconfirmed → app-level modal
            }
        }
    }

    /** The confirmation was cancelled or closed itself: drop the prepared batch, so the loop can give SMBs again. */
    fun discard(bolusId: Long) = batchExecutor.discard(bolusId)

    /** Confirm the master's prepared batch: deliver the parked bundle exactly once. */
    fun commit(bolusId: Long) {
        appScope.launch {
            val state = confirmedState
            val result = batchExecutor.commit(bolusId, Sources.InsulinDialog, rh.gs(InterfacesStrings.bolus))
            // Non-record: remove the bolus reminder on success. Record-only: only when not back/forward-dated.
            if (result is ActionProgress.Applied && (state == null || !state.recordOnlyChecked || state.timeOffsetMinutes == 0))
                automation.removeAutomationEventBolusReminder()
            // Surface a failed commit. NotReachable → the offline message; any other Rejected (ExecutionFailed,
            // NoPendingBolus, …) → the master's detail, which a client's app-level modal has shown already.
            // Unconfirmed (state unknown) rides the round-trip's app-level modal.
            if (result is ActionProgress.Rejected) {
                if (result.reason.isNotDeliveryError())
                    rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(result.reason.failText())))
                else if (!config.AAPSCLIENT) result.detail?.let { _sideEffect.tryEmit(SideEffect.ShowDeliveryError(it)) }
            }
        }
    }

    /** Runs the basal recording after the user confirmed its summary. */
    fun commitBasal(state: InsulinDialogUiState) {
        appScope.launch { recordBasalInjection(state) }
    }

    /** The summary the pre-4.0 insulin dialog showed before a Lantus injection was written. */
    private suspend fun basalConfirmationLines(state: InsulinDialogUiState): List<ConfirmationLine> {
        val previousFromNote = findLastBasalDose()
        val previousDose = previousFromNote ?: storedProfileBasalSum()
        return confirmationLines {
            val doseText = rh.gs(CoreUiStrings.value_with_unit, decimalFormatter.to0Decimal(state.insulin), rh.gs(CoreUiStrings.insulin_unit_shortname))
            line(ConfirmationRole.BOLUS, rh.gs(InterfacesStrings.confirmation_line, rh.gs(UiStrings.record_basal_insulin), doseText))
            if (previousDose != null) {
                // Mirror the rewrite decision: a profile-derived reference compares against the rate-quantized dose.
                val effectiveDose = if (previousFromNote == null) BasalInjection.flatRateTotal(state.insulin) else state.insulin
                if (!BasalInjection.matchesProfile(effectiveDose, previousDose))
                    line(
                        ConfirmationRole.WARNING,
                        rh.gs(
                            UiStrings.basal_insulin_profile_updated,
                            state.insulin, previousDose,
                            BasalInjection.flatRateTotal(state.insulin), BasalInjection.flatRate(state.insulin)
                        )
                    )
            }
            if (state.timeOffsetMinutes != 0)
                line(ConfirmationRole.NORMAL, rh.gs(InterfacesStrings.confirmation_line, rh.gs(CoreUiStrings.time), dateUtil.dateAndTimeString(state.eventTime)))
            if (state.showPosition)
                line(ConfirmationRole.INFO, rh.gs(InterfacesStrings.confirmation_line, rh.gs(InterfacesStrings.position_label), state.position.toString()))
            val notes = InjectionPosition.stripPosition(notesWithPosition(state)).trim()
            if (notes.isNotEmpty())
                line(ConfirmationRole.NORMAL, rh.gs(InterfacesStrings.confirmation_line, rh.gs(CoreUiStrings.notes_label), notes))
        }
    }

    private suspend fun recordBasalInjection(state: InsulinDialogUiState) {
        // Defensive: the picker and typed input already snap, but nothing may write a half unit.
        val dose = BasalInjection.snapDose(state.insulin)
        val max = preferences.get(DoubleKey.MdiMaxBasalInjection)
        if (dose > max) {
            rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(UiStrings.basal_insulin_above_max, max)))
            return
        }
        if (dose <= 0.0) {
            _sideEffect.tryEmit(SideEffect.ShowNoActionDialog)
            return
        }
        val time = state.eventTime
        val previousFromNote = findLastBasalDose()
        val storedProfileDose = storedProfileBasalSum()
        val previousDose = previousFromNote ?: storedProfileDose
        val doseText = decimalFormatter.to0Decimal(dose)
        val notes = notesWithPosition(state).trim()
        val basalNotes = "Lantus ${doseText}U" + if (notes.isNotEmpty()) " $notes" else ""
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
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
            listValues = listOf(ValueWithUnit.TEType(TE.Type.NOTE), ValueWithUnit.Insulin(dose))
        )
        if (previousDose != null) {
            val effectiveDose = if (previousFromNote == null) BasalInjection.flatRateTotal(dose) else dose
            if (!BasalInjection.matchesProfile(effectiveDose, previousDose))
                rewriteBasalProfile(dose, previousDose)
        }
        automation.removeAutomationEventBolusReminder()
        _sideEffect.tryEmit(SideEffect.NavigateBack)
    }

    private suspend fun lastInjectionPosition(): Int? {
        val from = dateUtil.now() - T.days(3).msecs()
        val boluses = persistenceLayer.getBolusesFromTimeToTime(from, dateUtil.now(), false)
        val events = persistenceLayer.getTherapyEventDataFromTime(from, TE.Type.NOTE, false)
        return InjectionPosition.findLastPosition(boluses, events)
    }

    private suspend fun findLastBasalDose(): Double? =
        try {
            persistenceLayer.getTherapyEventDataFromTime(dateUtil.now() - T.days(7).msecs(), TE.Type.NOTE, false)
                .sortedByDescending { it.timestamp }
                .firstNotNullOfOrNull { BasalInjection.extractDose(it.note) }
        } catch (e: Exception) {
            null
        }

    /** 24 h basal total of the stored (unscaled) profile a rewrite edits. */
    private suspend fun storedProfileBasalSum(): Double? {
        val name = profileFunction.getOriginalProfileName()
        val profile = profileRepository.profiles.value.firstOrNull { it.name == name } ?: return null
        return profile.basal.sumOf { it.amount * it.duration / T.hours(1).msecs() }
    }

    private suspend fun rewriteBasalProfile(amount: Double, previousDose: Double) {
        val profileName = profileFunction.getOriginalProfileName()
        val profiles = profileRepository.profiles.value
        val index = profiles.indexOfFirst { it.name == profileName }
        if (index < 0) {
            rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(UiStrings.basal_insulin_profile_update_failed, amount, previousDose)))
            return
        }
        val newFlatRate = BasalInjection.flatRate(amount)
        // Resolve everything the switch needs BEFORE the profile is rewritten, so a failure cannot leave the
        // stored profile updated without the matching profile switch (and without telling the user).
        val iCfg = profileFunction.getRunningOrRequestedICfg()
        if (iCfg == null) {
            rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(UiStrings.basal_insulin_profile_update_failed, amount, previousDose)))
            return
        }
        profileRepository.replace(
            index,
            profiles[index].copy(basal = listOf(Block(T.hours(24).msecs(), newFlatRate)))
        )
        val newStore = profileRepository.profile.value
        if (newStore == null) {
            rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(UiStrings.basal_insulin_profile_update_failed, amount, previousDose)))
            return
        }
        val now = dateUtil.now()
        // keep a running profile switch (percentage / time shift / remaining duration) alive across the rewrite
        val activeSwitch = persistenceLayer.getEffectiveProfileSwitchActiveAt(now)
            ?.takeIf { BasalInjection.isProfileSwitchCarryable(it.originalDuration, it.originalEnd, it.timestamp, now) }
        profileFunction.createProfileSwitch(
            profileStore = newStore,
            profileName = profileName,
            durationInMinutes = activeSwitch?.let { BasalInjection.remainingProfileSwitchMinutes(it.originalDuration, it.originalEnd, it.timestamp, now) } ?: 0,
            percentage = activeSwitch?.originalPercentage ?: 100,
            timeShiftInHours = activeSwitch?.let { T.msecs(it.originalTimeshift).hours().toInt() } ?: 0,
            timestamp = now,
            action = Action.PROFILE_SWITCH,
            source = Sources.InsulinDialog,
            note = rh.gs(UiStrings.record_basal_insulin),
            listValues = listOf(ValueWithUnit.SimpleString(profileName)),
            iCfg = iCfg
        )
        rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(UiStrings.basal_insulin_profile_updated, amount, previousDose, BasalInjection.flatRateTotal(amount), newFlatRate)))
    }

    /**
     * Build the batch from the dialog state. The bolus carries the user's RAW amount — the MASTER caps it (a client
     * never caps its own numbers); a record-only is kept as given. The eating-soon TT (target-LOWERING) travels with
     * the bolus so the master applies it only if the bolus is accepted (decision B). iCfg matters only for record-only.
     */
    private fun buildActions(state: InsulinDialogUiState): List<BatchAction> {
        val units = profileFunction.getUnits()
        val iCfg = if (state.recordOnlyChecked) state.selectedIcfg else null
        return buildList {
            if (!state.isMdi && state.eatingSoonTtChecked) {
                val mgdl = profileUtil.convertToMgdl(state.eatingSoonTtTarget, units)
                add(BatchAction.TempTarget(reason = TT.Reason.EATING_SOON.text, lowMgdl = mgdl, highMgdl = mgdl, durationMinutes = state.eatingSoonTtDuration, startOffsetMinutes = 0))
            }
            // Floor to the deliverable bolus step so the confirmed amount equals what the pump delivers (the
            // concentration boundary floors the converted cU to the native pulse grid). ch.bolusStep is the
            // step of the pump, converted by the concentration. Covers typed entry too.
            // A record-only dose (a pen) is not given by the pump, so it is recorded as typed: floored, a 1.5 U
            // pen dose at U200 (step 0.2 U) was saved as 1.4 U and IOB was too low.
            val insulin = if (state.recordOnlyChecked) state.insulin else Round.floorTo(state.insulin, ch.bolusStep(state.insulin))
            if (insulin > 0)
                add(
                    BatchAction.Bolus(
                        insulin = insulin, carbs = 0, carbsTimeOffsetMinutes = 0, carbsDurationHours = 0,
                        recordOnly = state.recordOnlyChecked, notes = notesWithPosition(state), timestamp = state.eventTime, iCfg = iCfg
                    )
                )
        }
    }
}
