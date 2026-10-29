package app.aaps.implementation.wizard

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.objects.wizard.ECarbsScheduler
import app.aaps.core.objects.wizard.MealMacroPlan
import app.aaps.core.objects.wizard.PendingCarbStore
import app.aaps.core.objects.wizard.PendingCarbs
import app.aaps.plugins.aps.openAPSSMB.OpenAPSSMBPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class BolusWizardTest : TestBaseWithProfile() {

    private val pumpBolusStep = 0.1

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var loop: Loop
    @Mock lateinit var autosensDataStore: AutosensDataStore
    @Mock lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock lateinit var openAPSSMBPlugin: OpenAPSSMBPlugin
    @Mock lateinit var uel: UserEntryLogger
    @Mock lateinit var automation: Automation
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var sp: SP
    private lateinit var pendingCarbStore: PendingCarbStore
    private lateinit var eCarbsScheduler: ECarbsScheduler
    private lateinit var profile: Profile

    @BeforeEach
    fun prepare() {
        whenever(activePlugin.activeAPS).thenReturn(openAPSSMBPlugin)
        pendingCarbStore = PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus)
        eCarbsScheduler = mock()
    }

    @Suppress("SameParameterValue")
    private fun setupProfile(targetLow: Double, targetHigh: Double, insulinSensitivityFactor: Double, insulinToCarbRatio: Double): Profile {
        val profile: Profile = mock()
        whenever(profile.getTargetLowMgdl()).thenReturn(targetLow)
        whenever(profile.getTargetLowMgdl()).thenReturn(targetHigh)
        whenever(profile.getIsfMgdlForCarbs(any(), any(), any(), any())).thenReturn(insulinSensitivityFactor)
        whenever(profile.getIc()).thenReturn(insulinToCarbRatio)

        whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(IobTotal(System.currentTimeMillis()))
        whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(System.currentTimeMillis()))
        testPumpPlugin.pumpDescription = PumpDescription().also {
            it.bolusStep = pumpBolusStep
        }
        whenever(iobCobCalculator.ads).thenReturn(autosensDataStore)

        doAnswer { invocation: InvocationOnMock ->
            invocation.getArgument<Constraint<Double>>(0)
        }.whenever(constraintChecker).applyBolusConstraints(anyOrNull())
        return profile
    }

    @Test
        /** Should calculate the same bolus when different blood glucose but both in target range  */
    fun shouldCalculateTheSameBolusWhenBGsInRange() {
        val profile = setupProfile(4.0, 8.0, 20.0, 12.0)
        var bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, pendingCarbStore, eCarbsScheduler
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                4.2,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBg42 = bw.calculatedTotalInsulin
        bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, pendingCarbStore, eCarbsScheduler
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                5.4,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBg54 = bw.calculatedTotalInsulin
        assertThat(bolusForBg54).isWithin(0.01).of(bolusForBg42)
    }

    @Test
    fun shouldCalculateHigherBolusWhenHighBG() {
        val profile = setupProfile(4.0, 8.0, 20.0, 12.0)
        var bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, pendingCarbStore, eCarbsScheduler
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                9.8,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForHighBg = bw.calculatedTotalInsulin
        bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, pendingCarbStore, eCarbsScheduler
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                5.4,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBgInRange = bw.calculatedTotalInsulin
        assertThat(bolusForHighBg).isGreaterThan(bolusForBgInRange)
    }

    @Test
    fun shouldCalculateLowerBolusWhenLowBG() {
        val profile = setupProfile(4.0, 8.0, 20.0, 12.0)
        var bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, pendingCarbStore, eCarbsScheduler
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                3.6,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForLowBg = bw.calculatedTotalInsulin
        bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, pendingCarbStore, eCarbsScheduler
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                5.4,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBgInRange = bw.calculatedTotalInsulin
        assertThat(bolusForLowBg).isLessThan(bolusForBgInRange)
    }

    @Test
    fun wizardWithCarbsIsBlockedWhileADeferredEntryIsPending() {
        pendingCarbStore.setPending(
            PendingCarbs(
                mainCarbs = DetailedBolusInfo().apply { carbs = 45.0 }
            )
        )
        val bw = BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData, pendingCarbStore, eCarbsScheduler
        ).apply { carbs = 50 }

        assertThat(bw.isBlockedByPendingCarbs()).isTrue()

        // A correction-only run must not be blocked: only meals could be logged twice
        bw.carbs = 0
        assertThat(bw.isBlockedByPendingCarbs()).isFalse()
    }

    @Test
    fun wizardWithCarbsIsNotBlockedWithoutADeferredEntry() {
        val bw = BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData, pendingCarbStore, eCarbsScheduler
        ).apply { carbs = 50 }

        assertThat(bw.isBlockedByPendingCarbs()).isFalse()
    }

    @Test
    fun wizardWithOnlyMacroTailsIsBlockedWhileADeferredEntryIsPending() {
        pendingCarbStore.setPending(PendingCarbs(mainCarbs = DetailedBolusInfo().apply { carbs = 45.0 }))
        val bw = newWizard().doCalc(
            profile, "", null, 0, 0.0, 5.4, 0.0, 100,
            useBg = false, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false,
            mealMacroPlan = macroPlan()
        )

        assertThat(bw.hasCarbsToLog).isTrue()
        assertThat(bw.isBlockedByPendingCarbs()).isTrue()
    }

    @Test
    fun advisorIsOfferedForAPlainHighBgMeal() {
        val bw = newWizard().doCalc(
            profile, "", null, 50, 0.0, 220.0, 0.0, 100,
            useBg = true, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false
        )

        assertThat(bw.isBolusAdvisorAvailable()).isTrue()
    }

    @Test
    fun advisorIsNotOfferedWhenAMealMacroPlanIsActive() {
        // The advisor logs a bolus only - it would drop the carbs and the eCarbs tails
        val bw = newWizard().doCalc(
            profile, "", null, 50, 0.0, 220.0, 0.0, 100,
            useBg = true, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false,
            mealMacroPlan = macroPlan()
        )

        assertThat(bw.isBolusAdvisorAvailable()).isFalse()
    }

    @Test
    fun advisorIsNotOfferedWhenCarbsAreDeferred() {
        val bw = newWizard().doCalc(
            profile, "", null, 50, 0.0, 220.0, 0.0, 100,
            useBg = true, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false,
            deferCarbs = true
        )

        assertThat(bw.isBolusAdvisorAvailable()).isFalse()
    }

    @Test
    fun advisorIsNotOfferedForCarbsInThePast() {
        val bw = newWizard().doCalc(
            profile, "", null, 50, 0.0, 220.0, 0.0, 100,
            useBg = true, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false,
            carbTime = -30
        )

        assertThat(bw.isBolusAdvisorAvailable()).isFalse()
    }

    private fun newWizard(pump: Pump? = null): BolusWizard {
        profile = setupProfile(4.0, 8.0, 20.0, 12.0)
        whenever(profile.units).thenReturn(GlucoseUnit.MGDL)
        whenever(preferences.get(BooleanKey.OverviewUseBolusAdvisor)).thenReturn(true)
        pump?.let { whenever(activePlugin.activePump).thenReturn(it) }
        return BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData, pendingCarbStore, eCarbsScheduler
        )
    }

    private fun mdiPump(): Pump = mock<Pump>().also {
        whenever(it.isMDI()).thenReturn(true)
        whenever(it.pumpDescription).thenReturn(PumpDescription().also { desc -> desc.bolusStep = pumpBolusStep })
    }

    @Test
    fun appliesThePercentageItIsGivenInMdiToo() {
        // 20 g at I:C 12 = 1.67 U. MDI callers pass 100 for the hidden partial-bolus pref, but an
        // explicit percentage (wizard % checkbox, QuickWizard entry, watch wizard) must still apply.
        val bw = newWizard(mdiPump()).doCalc(
            profile, "", null, 20, 0.0, 0.0, 0.0, 80,
            useBg = false, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false
        )

        assertThat(bw.calculatedTotalInsulin).isWithin(0.01).of(1.3)
    }

    @Test
    fun pumpWizardStillAppliesThePartialBolusPercentage() {
        val bw = newWizard().doCalc(
            profile, "", null, 20, 0.0, 0.0, 0.0, 80,
            useBg = false, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false
        )

        assertThat(bw.calculatedTotalInsulin).isWithin(0.01).of(1.3)
    }

    @Test
    fun percentageModeReportsTheAppliedPercentage() {
        // the wizard % field is re-seeded from calculatedPercentage - it must be the value that was
        // applied, not the class default, or toggling the control changes the dose
        whenever(constraintChecker.getMaxBolusAllowed()).thenReturn(ConstraintObject(10.0, aapsLogger))
        val bw = newWizard().doCalc(
            profile, "", null, 20, 0.0, 0.0, 0.0, 100,
            useBg = false, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false,
            usePercentage = true, totalPercentage = 80.0
        )

        assertThat(bw.calculatedPercentage).isEqualTo(80)
        assertThat(bw.percentageCorrection).isEqualTo(80)
        assertThat(bw.insulinAfterConstraints).isWithin(0.01).of(1.3)
    }

    private fun macroPlan() = MealMacroPlan(
        upfrontCarbs = 0,
        primaryTailCarbs = 40,
        fatTailCarbs = 6,
        suggestedUpfrontPercentage = 60,
        primaryTailShiftMin = 60,
        primaryTailDurationH = 4,
        fatTailShiftMin = 90,
        fatTailDurationH = 8,
        intensity = 1.0,
        fat = 40,
        protein = 80
    )

    @Test
    fun mealMacroPreviewShowsTheDosedInsulinAndShiftedTailTimes() {
        val plan = macroPlan().copy(upfrontCarbs = 70, primaryTailCarbs = 66)
        // doReturn: the base class already stubs gs(id, arg) with a formatting answer that needs a
        // stubbed template, so a plain whenever() would run it and fail on the unstubbed string
        doReturn("Now 70g").whenever(rh).gs(app.aaps.core.ui.R.string.wizard_meal_macro_preview_now, 70)
        doReturn("plan 60%").whenever(rh).gs(app.aaps.core.ui.R.string.wizard_meal_macro_preview_plan, 60)
        doReturn("2.4U").whenever(rh).gs(app.aaps.core.ui.R.string.wizard_meal_macro_preview_insulin, 2.4)
        doReturn("Tail 66g @ +70min/4h").whenever(rh).gs(app.aaps.core.ui.R.string.wizard_meal_macro_preview_tail, 66, 70, 4)
        doReturn("Fat-eq 6g @ +100min/8h").whenever(rh).gs(app.aaps.core.ui.R.string.wizard_meal_macro_preview_fat_tail, 6, 100, 8)

        // carb time +10 min shifts both tails by 10 min, exactly like ECarbsScheduler will
        assertThat(BolusWizard.mealMacroPreview(rh, plan, 2.4, 10))
            .isEqualTo("Now 70g · plan 60% · 2.4U · Tail 66g @ +70min/4h · Fat-eq 6g @ +100min/8h")
    }

    @Test
    fun mealMacroPreviewOmitsEmptyParts() {
        val plan = macroPlan().copy(upfrontCarbs = 0, fatTailCarbs = 0, suggestedUpfrontPercentage = 100)
        doReturn("Tail 40g @ +60min/4h").whenever(rh).gs(app.aaps.core.ui.R.string.wizard_meal_macro_preview_tail, 40, 60, 4)

        // no upfront carbs, nothing dosed now, no fat tail, the plan does not derate -> just the tail
        assertThat(BolusWizard.mealMacroPreview(rh, plan, 0.0, 0)).isEqualTo("Tail 40g @ +60min/4h")
    }

    @Test
    fun mealMacroPreviewAppliesAnExplicitWizardPercentage() {
        val plan = macroPlan().copy(upfrontCarbs = 70)
        doReturn("Now 70g").whenever(rh).gs(app.aaps.core.ui.R.string.wizard_meal_macro_preview_now, 70)
        doReturn("2.0U").whenever(rh).gs(app.aaps.core.ui.R.string.wizard_meal_macro_preview_insulin, 2.0)
        doReturn("Tail 40g @ +60min/4h").whenever(rh).gs(app.aaps.core.ui.R.string.wizard_meal_macro_preview_tail, 40, 60, 4)
        doReturn("Fat-eq 6g @ +90min/8h").whenever(rh).gs(app.aaps.core.ui.R.string.wizard_meal_macro_preview_fat_tail, 6, 90, 8)

        // insulinFromCarbs is pre-percentage: an explicit 80 % is what actually gets dosed, and the
        // plan's derate was skipped by the override, so its suggestion must not be advertised
        assertThat(BolusWizard.mealMacroPreview(rh, plan, 2.5, 0, percentage = 80.0, percentageOverride = true))
            .isEqualTo("Now 70g · 2.0U · Tail 40g @ +60min/4h · Fat-eq 6g @ +90min/8h")
    }
}
