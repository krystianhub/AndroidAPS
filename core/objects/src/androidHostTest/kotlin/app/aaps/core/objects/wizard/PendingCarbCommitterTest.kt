package app.aaps.core.objects.wizard

import app.aaps.core.data.ue.Action
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PendingCarbCommitterTest {

    private var stored = ""

    private val dateUtil = mock<DateUtil>().also { whenever(it.now()).thenReturn(1_000_000L) }
    private val preferences = mock<Preferences>().also {
        whenever(it.get(StringNonKey.PendingCarbs)).thenAnswer { stored }
        doAnswer { invocation ->
            stored = invocation.arguments[1] as String
            Unit
        }.whenever(it).putSync(any(), any())
    }
    private val notificationManager = mock<NotificationManager>()
    private val rh = mock<TextResolver>()
    private val store = PendingCarbStore(dateUtil, preferences, mock<AAPSLogger>(), rh, notificationManager)
    private val persistenceLayer = mock<PersistenceLayer>()
    private val uel = mock<UserEntryLogger>()

    private lateinit var committer: PendingCarbCommitter

    @BeforeEach
    fun setUp() {
        stored = ""
        committer = PendingCarbCommitter(
            aapsLogger = mock(),
            rh = rh,
            rxBus = mock<RxBus>(),
            uel = uel,
            persistenceLayer = persistenceLayer,
            uiInteraction = mock<UiInteraction>(),
            dateUtil = dateUtil,
            pendingCarbStore = store
        )
        runBlocking {
            whenever(persistenceLayer.insertOrUpdateCarbs(any(), any(), any(), any())).thenReturn(mock())
        }
    }

    private fun pending() = PendingCarbs(
        mainCarbs = DetailedBolusInfo().apply { carbs = 30.0 },
        createdAt = 0L
    )

    @Test
    fun `commit persists the carbs and clears the entry`() = runBlocking {
        store.setPending(pending())
        assertThat(committer.commit()).isTrue()
        assertThat(store.isPending()).isFalse()
    }

    @Test
    fun `commit without a pending entry is a no-op`() = runBlocking {
        assertThat(committer.commit()).isFalse()
    }
}
