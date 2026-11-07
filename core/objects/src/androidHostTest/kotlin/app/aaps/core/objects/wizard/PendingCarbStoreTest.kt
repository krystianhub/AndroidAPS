package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PendingCarbStoreTest {

    private var now = 1_000_000L
    private var stored = ""

    private val dateUtil = mock<DateUtil>().also {
        whenever(it.now()).thenAnswer { now }
    }
    private val aapsLogger = mock<AAPSLogger>()
    private val notificationManager = mock<NotificationManager>()
    private val preferences = mock<Preferences>().also {
        whenever(it.get(StringNonKey.PendingCarbs)).thenAnswer { stored }
        doAnswer { invocation ->
            stored = invocation.arguments[1] as String
            Unit
        }.whenever(it).putSync(any(), any())
    }

    private lateinit var store: PendingCarbStore

    @BeforeEach
    fun setUp() {
        stored = ""
        now = 1_000_000L
        store = PendingCarbStore(dateUtil, preferences, aapsLogger, notificationManager)
    }

    private fun entry(createdAt: Long = 0L): PendingCarbs =
        PendingCarbs(mainCarbs = app.aaps.core.interfaces.pump.DetailedBolusInfo().apply { carbs = 30.0 }, createdAt = createdAt)

    @Test
    fun `setPending persists the entry with a timestamp`() {
        store.setPending(entry())
        assertThat(store.isPending()).isTrue()
        assertThat(stored).isNotEmpty()
        assertThat(store.getPending()?.createdAt).isEqualTo(now)
    }

    @Test
    fun `clearPending removes it`() {
        store.setPending(entry())
        store.clearPending()
        assertThat(store.isPending()).isFalse()
        assertThat(stored).isEmpty()
    }

    @Test
    fun `entry expires after one hour and is reported once`() {
        store.setPending(entry())
        now += 60 * 60 * 1000L + 1
        assertThat(store.isPending()).isFalse()
        assertThat(store.consumeExpired()).isNotNull()
        assertThat(store.consumeExpired()).isNull()
    }

    @Test
    fun `beginCommit removes the persisted copy and a superseded token is ignored`() {
        store.setPending(entry())
        val claim = store.beginCommit()
        assertThat(claim).isNotNull()
        assertThat(stored).isEmpty()
        assertThat(store.commitSucceeded(claim!!.token + 1)).isFalse()
        assertThat(store.commitSucceeded(claim.token)).isTrue()
        assertThat(store.isPending()).isFalse()
    }

    @Test
    fun `an unreadable entry is discarded and reported`() {
        stored = "not json"
        assertThat(store.isPending()).isFalse()
        assertThat(stored).isEmpty()
    }
}
