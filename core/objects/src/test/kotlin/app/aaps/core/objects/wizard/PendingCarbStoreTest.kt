package app.aaps.core.objects.wizard

import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
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
    }

    private lateinit var store: PendingCarbStore

    @BeforeEach
    fun setUp() {
        // Reset the shared SP map and clock so tests do not leak state into each other
        prefs.clear()
        now = 1_000_000L
        // Stubbing calls are recorded by Mockito; clear them so verify() only sees real interactions
        clearInvocations(sp)
        store = PendingCarbStore(dateUtil, sp)
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
        quickWizardECarbs = QuickWizardECarbs(
            carbs2 = 12,
            timeOffsetMin = 30,
            durationH = 2,
            notes = "qw note"
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
        val restarted = PendingCarbStore(dateUtil, sp)
        assertThat(restarted.isPending()).isFalse()
        assertThat(restarted.getPending()).isNull()
    }

    // endregion

    // region persistence / round-trip

    @Test
    fun `pending survives a store recreation`() {
        store.setPending(pending())
        // A brand-new instance reading the same preferences simulates a process restart
        val restarted = PendingCarbStore(dateUtil, sp)
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
        val restored = PendingCarbStore(dateUtil, sp).getPending()!!

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
        val plan = PendingCarbStore(dateUtil, sp).getPending()!!.mealPlan!!

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
    fun `round trip preserves the quick wizard eCarbs`() {
        store.setPending(fullPending())
        val qw = PendingCarbStore(dateUtil, sp).getPending()!!.quickWizardECarbs!!

        assertThat(qw.carbs2).isEqualTo(12)
        assertThat(qw.timeOffsetMin).isEqualTo(30)
        assertThat(qw.durationH).isEqualTo(2)
        assertThat(qw.notes).isEqualTo("qw note")
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
        val restored = PendingCarbStore(dateUtil, sp).getPending()!!

        assertThat(restored.mainCarbs.notes).isNull()
        assertThat(restored.mainCarbs.mgdlGlucose).isNull()
        assertThat(restored.mainCarbs.glucoseType).isNull()
        assertThat(restored.mealPlan).isNull()
        assertThat(restored.quickWizardECarbs).isNull()
        assertThat(restored.source).isEqualTo(Sources.WizardDialog)
    }

    @Test
    fun `setting a new entry overwrites the previous one`() {
        store.setPending(pending(10.0))
        store.setPending(pending(99.0))
        assertThat(store.getPending()?.mainCarbs?.carbs).isEqualTo(99.0)
        // The persisted copy must reflect the latest value too
        assertThat(PendingCarbStore(dateUtil, sp).getPending()?.mainCarbs?.carbs).isEqualTo(99.0)
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

        val restarted = PendingCarbStore(dateUtil, sp)
        restarted.isPending()
        restarted.isPending()
        restarted.getPending()

        verify(sp, times(1)).getStringOrNull(anyString(), anyOrNull())
    }

    @Test
    fun `set pending writes to SP`() {
        clearInvocations(sp)
        store.setPending(pending())
        verify(sp, times(1)).putString(anyString(), anyString())
    }

    @Test
    fun `clear pending removes from SP`() {
        store.setPending(pending())
        clearInvocations(sp)
        store.clearPending()
        verify(sp, times(1)).remove(anyString())
    }

    @Test
    fun `expiry during read does not write to SP`() {
        store.setPending(pending())
        now += 60 * 60 * 1000L + 1
        clearInvocations(sp)

        assertThat(store.isPending()).isFalse()

        // Expiry only drops the in-memory copy; the read path must not touch SP
        verify(sp, never()).remove(anyString())
        verify(sp, never()).putString(anyString(), anyString())
    }

    @Test
    fun `clear on an empty store still removes the SP key`() {
        clearInvocations(sp)
        store.clearPending()
        verify(sp, times(1)).remove(anyString())
    }

    // endregion

    // region atomic commit

    @Test
    fun `beginCommit returns the pending entry`() {
        store.setPending(pending())
        val claimed = store.beginCommit()
        assertThat(claimed).isNotNull()
        assertThat(claimed?.mainCarbs?.carbs).isEqualTo(50.0)
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
        store.beginCommit()
        store.commitSucceeded()
        assertThat(store.isPending()).isFalse()
        assertThat(store.getPending()).isNull()
    }

    @Test
    fun `commitFailed keeps the entry so it can be retried`() {
        store.setPending(pending())
        store.beginCommit()
        store.commitFailed()
        assertThat(store.isPending()).isTrue()
        assertThat(store.beginCommit()).isNotNull()
    }

    @Test
    fun `setPending clears a commit in progress`() {
        store.setPending(pending())
        store.beginCommit()
        store.setPending(pending(99.0))
        assertThat(store.beginCommit()?.mainCarbs?.carbs).isEqualTo(99.0)
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
        verify(sp, times(1)).remove(anyString())
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

        val restarted = PendingCarbStore(dateUtil, sp)
        assertThat(restarted.isPending()).isFalse()
        assertThat(restarted.consumeExpired()).isNotNull()
        verify(sp, times(1)).remove(anyString())
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
