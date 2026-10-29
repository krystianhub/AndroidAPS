package app.aaps.core.interfaces.utils

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.db.PersistenceLayer
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApsResultsTest {

    private val now = 1_700_000_000_000L
    private val windowMs = T.mins(15).msecs()

    @Test
    fun `median averages the two middle values of an even sample count`() {
        assertThat(ApsResults.median(listOf(3.0, 1.0, 2.0, 6.0))).isEqualTo(2.5)
    }

    @Test
    fun `median of an odd sample count is the middle value`() {
        assertThat(ApsResults.median(listOf(5.0, 0.0, 2.0))).isEqualTo(2.0)
    }

    @Test
    fun `median of an empty list is null`() {
        assertThat(ApsResults.median(emptyList())).isNull()
    }

    @Test
    fun `recent drops a persisted twin newer than the live run`() {
        val persistenceLayer = mock<PersistenceLayer>()
        // the run started 2 s before it was persisted
        val liveTimestamp = now - 2_000L
        val persisted = apsResult(now)
        whenever(persistenceLayer.getApsResults(anyLong(), anyLong())).thenReturn(listOf(persisted))
        val live = apsResult(liveTimestamp)

        val recent = ApsResults.recent(persistenceLayer, now, windowMs, liveTimestamp to live)

        assertThat(recent.map { it.first }).containsExactly(liveTimestamp)
        assertThat(recent.single().second).isSameInstanceAs(live)
    }

    @Test
    fun `recent drops a persisted twin older than the live run`() {
        val persistenceLayer = mock<PersistenceLayer>()
        val persisted = apsResult(now - 2_000L)
        whenever(persistenceLayer.getApsResults(anyLong(), anyLong())).thenReturn(listOf(persisted))
        val live = apsResult(now)

        val recent = ApsResults.recent(persistenceLayer, now, windowMs, now to live)

        assertThat(recent.map { it.first }).containsExactly(now)
        assertThat(recent.single().second).isSameInstanceAs(live)
    }

    @Test
    fun `recent keeps a persisted run that is no twin of the live run`() {
        val persistenceLayer = mock<PersistenceLayer>()
        val older = apsResult(now - T.mins(5).msecs())
        whenever(persistenceLayer.getApsResults(anyLong(), anyLong())).thenReturn(listOf(older))
        val live = apsResult(now)

        val recent = ApsResults.recent(persistenceLayer, now, windowMs, now to live)

        assertThat(recent.map { it.first }).containsExactly(now - T.mins(5).msecs(), now)
    }

    @Test
    fun `recent returns persisted results without a live run`() {
        val persistenceLayer = mock<PersistenceLayer>()
        val persisted = apsResult(now - T.mins(5).msecs())
        whenever(persistenceLayer.getApsResults(anyLong(), anyLong())).thenReturn(listOf(persisted))

        val recent = ApsResults.recent(persistenceLayer, now, windowMs)

        assertThat(recent.map { it.first }).containsExactly(now - T.mins(5).msecs())
    }

    private fun apsResult(timestamp: Long): APSResult = mock<APSResult>().also {
        whenever(it.date).thenReturn(timestamp)
    }
}
