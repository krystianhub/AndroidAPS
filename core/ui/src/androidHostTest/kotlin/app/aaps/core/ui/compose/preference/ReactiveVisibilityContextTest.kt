package app.aaps.core.ui.compose.preference

import androidx.compose.runtime.mutableStateMapOf
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.VisibilityContext
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/** The preference machinery wraps the visibility context; it must forward the MDI flag. */
class ReactiveVisibilityContextTest {

    private fun context(isMdi: Boolean) = object : VisibilityContext {
        override val isMdi = isMdi
        override val isPatchPump = false
        override val isBatteryReplaceable = false
        override val isBatteryChangeLoggingEnabled = false
        override val isRefillingCapable = false
        override val advancedFilteringSupported = false
        override val preferences: Preferences = mock()
    }

    @Test
    fun `MDI_only survives the reactive wrapper`() {
        val reactive = ReactiveVisibilityContext(context(isMdi = true), mock(), mutableStateMapOf())
        assertThat(ElementVisibility.MDI_ONLY.isVisible(reactive)).isTrue()
        assertThat(ElementVisibility.NON_MDI.isVisible(reactive)).isFalse()
    }

    @Test
    fun `non-MDI context keeps MDI settings hidden`() {
        val reactive = ReactiveVisibilityContext(context(isMdi = false), mock(), mutableStateMapOf())
        assertThat(ElementVisibility.MDI_ONLY.isVisible(reactive)).isFalse()
        assertThat(ElementVisibility.NON_MDI.isVisible(reactive)).isTrue()
    }
}
