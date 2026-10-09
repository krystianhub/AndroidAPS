package app.aaps.core.interfaces.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ApsResultsTest {

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
}
