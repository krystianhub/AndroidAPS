package app.aaps.core.objects.wizard

import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PendingCarbStoreTest {

    private var now = 1_000_000L
    private val prefs = mutableMapOf<String, String>()

    private val dateUtil = mock<DateUtil>().also {
        whenever(it.now()).thenAnswer { now }
    }

    private val aapsLogger = mock<AAPSLogger>()
    private val rh = mock<ResourceHelper>().also {
        whenever(it.gs(any<Int>())).thenReturn("unreadable")
    }
    private val rxBus = mock<RxBus>()

    private val sp = mock<SP>().also {
        whenever(it.getStringOrNull(anyString(), anyOrNull<String>())).thenAnswer { prefs[it.arguments[0] as String] }
        doAnswer {
            prefs[it.arguments[0] as String] = it.arguments[1] as String
            Unit
        }.whenever(it).putString(anyString(), anyString())
        doAnswer {
            prefs.remove(it.arguments[0] as String)
            Unit
        }.whenever(it).remove(anyString())
        doAnswer { invocation ->
            val block = invocation.getArgument<(SP.Editor) -> Unit>(1)
            block(newEditor())
            Unit
        }.whenever(it).edit(any(), any())
    }

    /** Editor that applies to the same in-memory map the SP mock reads from. */
    private fun newEditor(): SP.Editor = mock<SP.Editor>().also { e ->
        doAnswer { invocation ->
            prefs[invocation.arguments[0] as String] = invocation.arguments[1] as String
            Unit
        }.whenever(e).putString(anyString(), anyString())
        doAnswer { invocation ->
            prefs.remove(invocation.arguments[0] as String)
            Unit
        }.whenever(e).remove(anyString())
    }

    private lateinit var store: PendingCarbStore

    @BeforeEach
    fun setUp() {
        // Reset the shared SP map and clock so tests do not leak state into each other
        prefs.clear()
        now = 1_000_000L
        // Stubbing calls are recorded by Mockito; clear them so verify() only sees real interactions
        clearInvocations(sp)
        store = PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus)
    }

    private fun pending(amount: Double = 50.0) = PendingCarbs(
        mainCarbs = DetailedBolusInfo().apply { carbs = amount }
    )

    private fun fullPending() = PendingCarbs(
        mainCarbs = DetailedBolusInfo().apply {
            carbs = 45.5
            carbsTimestamp = 1_234_567_890L
            carbsDuration = 3_600_000L
            notes = "test meal"
            eventType = TE.Type.MEAL_BOLUS
            mgdlGlucose = 120.0
            glucoseType = TE.MeterType.FINGER
        },
        mealPlan = MealMacroPlan(
            upfrontCarbs = 30,
            primaryTailCarbs = 15,
            fatTailCarbs = 10,
            suggestedUpfrontPercentage = 70,
            primaryTailShiftMin = 60,
            primaryTailDurationH = 3,
            fatTailShiftMin = 120,
            fatTailDurationH = 4,
            intensity = 0.65,
            fat = 20,
            protein = 25
        ),
        source = Sources.QuickWizard
    )

    // region basic lifecycle

    @Test
    fun `empty store is not pending`() {
        assertThat(store.isPending()).isFalse()
        assertThat(store.getPending()).isNull()
    }

    @Test
    fun `set pending makes it pending`() {
        store.setPending(pending())
        assertThat(store.isPending()).isTrue()
        assertThat(store.getPending()).isNotNull()
    }

    @Test
    fun `clear pending empties the store`() {
        store.setPending(pending())
        store.clearPending()
        assertThat(store.isPending()).isFalse()
        assertThat(store.getPending()).isNull()
    }

    // endregion

    // region expiry

    @Test
    fun `pending expires after one hour`() {
        store.setPending(pending())
        now += 60 * 60 * 1000L + 1
        assertThat(store.isPending()).isFalse()
        assertThat(store.getPending()).isNull()
    }

    @Test
    fun `pending stays valid just before one hour`() {
        store.setPending(pending())
        now += 60 * 60 * 1000L - 1
        assertThat(store.isPending()).isTrue()
    }

    @Test
    fun `expired entry is not loaded from SP on a fresh instance`() {
        store.setPending(pending())
        now += 60 * 60 * 1000L + 1
        val restarted = PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus)
        assertThat(restarted.isPending()).isFalse()
        assertThat(restarted.getPending()).isNull()
    }

    // endregion

    // region persistence / round-trip

    @Test
    fun `pending survives a store recreation`() {
        store.setPending(pending())
        // A brand-new instance reading the same preferences simulates a process restart
        val restarted = PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus)
        assertThat(restarted.isPending()).isTrue()
        assertThat(restarted.getPending()?.mainCarbs?.carbs).isEqualTo(50.0)
    }

    @Test
    fun `set pending stamps createdAt with the current time`() {
        now = 1_234_567L
        store.setPending(pending())
        assertThat(store.getPending()?.createdAt).isEqualTo(1_234_567L)
    }

    @Test
    fun `round trip preserves all main carbs fields`() {
        store.setPending(fullPending())
        val restored = PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus).getPending()!!

        assertThat(restored.mainCarbs.carbs).isEqualTo(45.5)
        assertThat(restored.mainCarbs.carbsTimestamp).isEqualTo(1_234_567_890L)
        assertThat(restored.mainCarbs.carbsDuration).isEqualTo(3_600_000L)
        assertThat(restored.mainCarbs.notes).isEqualTo("test meal")
        assertThat(restored.mainCarbs.eventType).isEqualTo(TE.Type.MEAL_BOLUS)
        assertThat(restored.mainCarbs.mgdlGlucose).isEqualTo(120.0)
        assertThat(restored.mainCarbs.glucoseType).isEqualTo(TE.MeterType.FINGER)
        assertThat(restored.source).isEqualTo(Sources.QuickWizard)
    }

    @Test
    fun `round trip preserves the meal macro plan`() {
        store.setPending(fullPending())
        val plan = PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus).getPending()!!.mealPlan!!

        assertThat(plan.upfrontCarbs).isEqualTo(30)
        assertThat(plan.primaryTailCarbs).isEqualTo(15)
        assertThat(plan.fatTailCarbs).isEqualTo(10)
        assertThat(plan.suggestedUpfrontPercentage).isEqualTo(70)
        assertThat(plan.primaryTailShiftMin).isEqualTo(60)
        assertThat(plan.primaryTailDurationH).isEqualTo(3)
        assertThat(plan.fatTailShiftMin).isEqualTo(120)
        assertThat(plan.fatTailDurationH).isEqualTo(4)
        assertThat(plan.intensity).isEqualTo(0.65)
        assertThat(plan.fat).isEqualTo(20)
        assertThat(plan.protein).isEqualTo(25)
    }

    @Test
    fun `round trip preserves null optional fields`() {
        val minimal = PendingCarbs(
            mainCarbs = DetailedBolusInfo().apply {
                carbs = 20.0
                notes = null
                mgdlGlucose = null
                glucoseType = null
            }
        )
        store.setPending(minimal)
        val restored = PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus).getPending()!!

        assertThat(restored.mainCarbs.notes).isNull()
        assertThat(restored.mainCarbs.mgdlGlucose).isNull()
        assertThat(restored.mainCarbs.glucoseType).isNull()
        assertThat(restored.mealPlan).isNull()
        assertThat(restored.source).isEqualTo(Sources.WizardDialog)
    }

    @Test
    fun `setting a new entry overwrites the previous one`() {
        store.setPending(pending(10.0))
        store.setPending(pending(99.0))
        assertThat(store.getPending()?.mainCarbs?.carbs).isEqualTo(99.0)
        // The persisted copy must reflect the latest value too
        assertThat(PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus).getPending()?.mainCarbs?.carbs).isEqualTo(99.0)
    }

    // endregion

    // region corrupt / invalid data

    @Test
    fun `corrupt JSON in SP is treated as no pending entry`() {
        prefs["pending_carbs_entry"] = "this is not json"
        assertThat(store.isPending()).isFalse()
        assertThat(store.getPending()).isNull()
    }

    @Test
    fun `zero carbs in SP is treated as no pending entry`() {
        prefs["pending_carbs_entry"] = """{"carbs":0.0,"createdAt":$now}"""
        assertThat(store.isPending()).isFalse()
        assertThat(store.getPending()).isNull()
    }

    @Test
    fun `negative carbs in SP is treated as no pending entry`() {
        prefs["pending_carbs_entry"] = """{"carbs":-5.0,"createdAt":$now}"""
        assertThat(store.isPending()).isFalse()
        assertThat(store.getPending()).isNull()
    }

    @Test
    fun `zero main carbs with a meal plan is a valid entry`() {
        // A heavy meal whose upfront share truncates to 0 g only has tails to log
        prefs["pending_carbs_entry"] =
            """{"carbs":0.0,"createdAt":$now,"plan":{"upfrontCarbs":0,"primaryTailCarbs":40,"fatTailCarbs":6}}"""
        val pending = store.getPending()
        assertThat(pending).isNotNull()
        assertThat(pending!!.mainCarbs.carbs).isEqualTo(0.0)
        assertThat(pending.mealPlan!!.primaryTailCarbs).isEqualTo(40)
        assertThat(pending.mealPlan!!.hasTails).isTrue()
    }

    @Test
    fun `unreadable entry is discarded and reported to the user`() {
        prefs["pending_carbs_entry"] = """{"carbs":50.0,"eventType":"NOT_A_TYPE","createdAt":$now}"""
        clearInvocations(sp)

        assertThat(store.isPending()).isFalse()

        // The persisted copy is dropped so the buttons are not permanently hidden by a dead entry
        assertThat(prefs["pending_carbs_entry"]).isNull()
        verify(sp, times(1)).edit(eq(true), any())
        verify(rxBus, times(1)).send(any())
    }

    @Test
    fun `corrupt JSON is reported to the user`() {
        prefs["pending_carbs_entry"] = "this is not json"

        assertThat(store.isPending()).isFalse()

        assertThat(prefs["pending_carbs_entry"]).isNull()
        verify(rxBus, times(1)).send(any())
    }

    // endregion

    // region caching / SP interaction

    @Test
    fun `SP is read only once across repeated reads`() {
        store.setPending(pending())
        clearInvocations(sp)

        store.isPending()
        store.isPending()
        store.getPending()
        store.getPending()

        verify(sp, times(0)).getStringOrNull(anyString(), anyOrNull())
    }

    @Test
    fun `fresh instance reads SP exactly once for repeated reads`() {
        store.setPending(pending())
        clearInvocations(sp)

        val restarted = PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus)
        restarted.isPending()
        restarted.isPending()
        restarted.getPending()

        verify(sp, times(1)).getStringOrNull(anyString(), anyOrNull())
    }

    @Test
    fun `set pending writes to SP`() {
        clearInvocations(sp)
        store.setPending(pending())
        verify(sp, times(1)).edit(eq(true), any())
        assertThat(prefs["pending_carbs_entry"]).isNotNull()
    }

    @Test
    fun `clear pending removes from SP`() {
        store.setPending(pending())
        clearInvocations(sp)
        store.clearPending()
        verify(sp, times(1)).edit(eq(true), any())
        assertThat(prefs["pending_carbs_entry"]).isNull()
    }

    @Test
    fun `expiry during read does not write to SP`() {
        store.setPending(pending())
        now += 60 * 60 * 1000L + 1
        clearInvocations(sp)

        assertThat(store.isPending()).isFalse()

        // Expiry only drops the in-memory copy; the read path must not touch SP
        verify(sp, never()).edit(any(), any())
    }

    @Test
    fun `clear on an empty store still removes the SP key`() {
        clearInvocations(sp)
        store.clearPending()
        verify(sp, times(1)).edit(eq(true), any())
    }

    // endregion

    // region atomic commit

    @Test
    fun `beginCommit returns the pending entry`() {
        store.setPending(pending())
        val claim = store.beginCommit()
        assertThat(claim).isNotNull()
        assertThat(claim?.pending?.mainCarbs?.carbs).isEqualTo(50.0)
    }

    @Test
    fun `beginCommit while a commit is running returns null`() {
        store.setPending(pending())
        assertThat(store.beginCommit()).isNotNull()
        assertThat(store.beginCommit()).isNull()
    }

    @Test
    fun `beginCommit with nothing pending returns null`() {
        assertThat(store.beginCommit()).isNull()
    }

    @Test
    fun `commitSucceeded clears the pending entry`() {
        store.setPending(pending())
        val claim = store.beginCommit()!!
        assertThat(store.commitSucceeded(claim.token)).isTrue()
        assertThat(store.isPending()).isFalse()
        assertThat(store.getPending()).isNull()
    }

    @Test
    fun `commitFailed keeps the entry so it can be retried`() {
        store.setPending(pending())
        store.commitFailed(store.beginCommit()!!.token)
        assertThat(store.isPending()).isTrue()
        assertThat(store.beginCommit()).isNotNull()
    }

    @Test
    fun `setPending clears a commit in progress`() {
        store.setPending(pending())
        store.beginCommit()
        store.setPending(pending(99.0))
        assertThat(store.beginCommit()?.pending?.mainCarbs?.carbs).isEqualTo(99.0)
    }

    @Test
    fun `beginCommit removes the persisted copy`() {
        store.setPending(pending())
        assertThat(store.beginCommit()).isNotNull()

        // At-least-once: a process death between the claim and the DB write loses the carbs,
        // but it can never insert them twice
        assertThat(prefs["pending_carbs_entry"]).isNull()
        // The claimed entry stays available in memory until the commit finishes
        assertThat(store.getPending()?.mainCarbs?.carbs).isEqualTo(50.0)
    }

    @Test
    fun `the claim removal is committed synchronously`() {
        store.setPending(pending())
        clearInvocations(sp)

        store.beginCommit()

        // commit = true: an async apply() could be lost on a process kill and resurrect a consumed entry
        verify(sp, times(1)).edit(eq(true), any())
        assertThat(prefs["pending_carbs_entry"]).isNull()
    }

    @Test
    fun `commitFailed restores the persisted copy`() {
        store.setPending(pending())
        store.commitFailed(store.beginCommit()!!.token)

        assertThat(prefs["pending_carbs_entry"]).isNotNull()
        // ... so the entry also survives a process restart after a failed write
        assertThat(PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus).isPending()).isTrue()
    }

    @Test
    fun `claim older than a minute can be taken over`() {
        store.setPending(pending())
        assertThat(store.beginCommit()).isNotNull()
        assertThat(store.beginCommit()).isNull()

        // An Rx callback that never fires must not block "Eat now" forever
        now += 60 * 1000L + 1
        assertThat(store.beginCommit()).isNotNull()
    }

    @Test
    fun `a superseded claim cannot clear the entry it no longer owns`() {
        store.setPending(pending())
        val stale = store.beginCommit()!!
        // the write seems to hang, the user retries and takes the claim over
        now += 60 * 1000L + 1
        val fresh = store.beginCommit()!!

        // the stale callback must be a no-op, otherwise it would wipe the new claim and let the
        // committer schedule the meal-macro tails a second time
        assertThat(store.commitSucceeded(stale.token)).isFalse()
        assertThat(store.isPending()).isTrue()
        assertThat(store.commitSucceeded(fresh.token)).isTrue()
        assertThat(store.isPending()).isFalse()
    }

    @Test
    fun `a superseded claim cannot restore a cancelled entry`() {
        store.setPending(pending())
        val stale = store.beginCommit()!!
        store.clearPending()   // user pressed Cancel while the write was in flight

        store.commitFailed(stale.token)

        assertThat(store.isPending()).isFalse()
        assertThat(prefs["pending_carbs_entry"]).isNull()
    }

    @Test
    fun `claim within a minute is not taken over`() {
        store.setPending(pending())
        assertThat(store.beginCommit()).isNotNull()

        now += 60 * 1000L
        assertThat(store.beginCommit()).isNull()
    }

    // endregion

    // region expiry notification

    @Test
    fun `consumeExpired returns the expired entry and removes it from SP`() {
        store.setPending(pending())
        now += 60 * 60 * 1000L + 1
        clearInvocations(sp)

        assertThat(store.isPending()).isFalse()
        val expired = store.consumeExpired()
        assertThat(expired).isNotNull()
        assertThat(expired?.mainCarbs?.carbs).isEqualTo(50.0)
        verify(sp, times(1)).edit(eq(true), any())
        assertThat(prefs["pending_carbs_entry"]).isNull()
    }

    @Test
    fun `consumeExpired returns null when nothing expired`() {
        store.setPending(pending())
        assertThat(store.consumeExpired()).isNull()
    }

    @Test
    fun `expired entry is consumed only once`() {
        store.setPending(pending())
        now += 60 * 60 * 1000L + 1
        assertThat(store.isPending()).isFalse()

        assertThat(store.consumeExpired()).isNotNull()
        assertThat(store.consumeExpired()).isNull()
    }

    @Test
    fun `consumeExpired after a fresh instance loads an expired SP entry`() {
        store.setPending(pending())
        now += 60 * 60 * 1000L + 1
        clearInvocations(sp)

        val restarted = PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus)
        assertThat(restarted.isPending()).isFalse()
        assertThat(restarted.consumeExpired()).isNotNull()
        verify(sp, times(1)).edit(eq(true), any())
    }

    @Test
    fun `setPending clears a previously expired marker`() {
        store.setPending(pending())
        now += 60 * 60 * 1000L + 1
        assertThat(store.isPending()).isFalse()

        store.setPending(pending(99.0))
        assertThat(store.consumeExpired()).isNull()
        assertThat(store.isPending()).isTrue()
    }

    // endregion
}
