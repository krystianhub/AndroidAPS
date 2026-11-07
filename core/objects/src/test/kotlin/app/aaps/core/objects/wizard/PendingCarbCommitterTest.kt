package app.aaps.core.objects.wizard

import app.aaps.core.data.model.CA
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PendingCarbCommitterTest {

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
        // the store writes/removes the pending entry through edit(commit = true)
        doAnswer { invocation ->
            val block = invocation.getArgument<(SP.Editor) -> Unit>(1)
            block(newEditor())
            Unit
        }.whenever(it).edit(any(), any())
    }

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

    private val aapsLogger = mock<AAPSLogger>()
    private val rh = mock<ResourceHelper>()
    private val rxBus = mock<RxBus>()
    private val uel = mock<UserEntryLogger>()
    private val persistenceLayer = mock<PersistenceLayer>()
    private val uiInteraction = mock<UiInteraction>()

    private lateinit var store: PendingCarbStore
    private lateinit var committer: PendingCarbCommitter

    @BeforeEach
    fun setUp() {
        prefs.clear()
        now = 1_000_000L
        clearInvocations(sp)
        store = PendingCarbStore(dateUtil, sp, aapsLogger, rh, rxBus)
        committer = PendingCarbCommitter(aapsLogger, rh, rxBus, uel, persistenceLayer, uiInteraction, dateUtil, store)
        whenever(persistenceLayer.insertOrUpdateCarbs(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Single.just(PersistenceLayer.TransactionResult<CA>()))
    }

    private fun pending(carbs: Double = 45.0) = PendingCarbs(
        mainCarbs = DetailedBolusInfo().apply {
            this.carbs = carbs
            notes = "test meal"
        },
        source = Sources.WizardDialog
    )

    @Test
    fun `commit logs the carbs and clears the pending entry`() {
        store.setPending(pending())

        assertThat(committer.commit()).isTrue()

        verify(persistenceLayer).insertOrUpdateCarbs(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        assertThat(store.isPending()).isFalse()
    }

    @Test
    fun `commit stamps the carbs timestamp with the commit time`() {
        store.setPending(pending())
        now = 2_000_000L

        committer.commit()

        assertThat(store.getPending()).isNull()
        val captor = argumentCaptor<CA>()
        verify(persistenceLayer).insertOrUpdateCarbs(captor.capture(), anyOrNull(), anyOrNull(), anyOrNull())
        assertThat(captor.firstValue.timestamp).isEqualTo(2_000_000L)
    }

    @Test
    fun `commit without anything pending does nothing`() {
        assertThat(committer.commit()).isFalse()

        verify(persistenceLayer, never()).insertOrUpdateCarbs(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `commit of an expired entry does nothing`() {
        store.setPending(pending())
        now += 60 * 60 * 1000L + 1

        assertThat(committer.commit()).isFalse()

        verify(persistenceLayer, never()).insertOrUpdateCarbs(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        // The expiry is still reportable to the user
        assertThat(store.consumeExpired()).isNotNull()
    }

    @Test
    fun `commit is refused while another commit is running`() {
        store.setPending(pending())
        assertThat(store.beginCommit()).isNotNull()

        assertThat(committer.commit()).isFalse()

        verify(persistenceLayer, never()).insertOrUpdateCarbs(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `failed write keeps the entry so it can be retried`() {
        store.setPending(pending())
        whenever(persistenceLayer.insertOrUpdateCarbs(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Single.error(RuntimeException("db error")))

        assertThat(committer.commit()).isTrue()

        assertThat(store.isPending()).isTrue()
        // The claim must be released again, otherwise "Eat now" would stay dead
        assertThat(store.beginCommit()).isNotNull()
    }

}
