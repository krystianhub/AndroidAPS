package app.aaps.ui.compose.overview.chips

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsSpacing
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.ui.R as CoreUiR

/**
 * Robolectric test for [IobCobChipsRow]: the icons give way when the row is too narrow, except
 * while carbs are required, because the blinking COB icon is the cue for that.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class IobCobChipsRowTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var iobLabel: String
    private lateinit var cobLabel: String

    @Before
    fun setUp() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        iobLabel = ctx.getString(CoreUiR.string.iob)
        cobLabel = ctx.getString(CoreUiR.string.cob)
    }

    /** The row is too narrow for the icons: proves that [cobIconStaysWhileCarbsAreRequired] tests a real squeeze. */
    @Test
    fun iconsAreDroppedWhenTheRowIsTooNarrow() {
        setRow(carbsReq = 0)
        assertThat(cobTextOffset().value).isLessThan(AapsSpacing.chipIconSize.value)
    }

    /** Same width, same text, but carbs are required: the icon that blinks must stay. */
    @Test
    fun cobIconStaysWhileCarbsAreRequired() {
        setRow(carbsReq = 16)
        assertThat(cobTextOffset().value).isGreaterThan(AapsSpacing.chipIconSize.value)
    }

    /** Without a request the two pills stay the same width. */
    @Test
    fun equalWidthsWithoutACarbsRequest() {
        setRow(carbsReq = 0, width = WIDE_ROW)
        assertThat(placedWidth(hasContentDescription(cobLabel)).value)
            .isWithin(0.5f).of(placedWidth(hasContentDescription(iobLabel)).value)
    }

    /** The appended "16 req" needs room, so COB takes the larger share instead of scrolling. */
    @Test
    fun carbsRequestGivesCobTheWiderChip() {
        setRow(carbsReq = 16, width = WIDE_ROW)
        assertThat(placedWidth(hasContentDescription(cobLabel)).value)
            .isGreaterThan(placedWidth(hasContentDescription(iobLabel)).value)
    }

    /** Distance from the left edge of the COB chip to its text: more than an icon when the icon is there. */
    private fun cobTextOffset(): Dp =
        placedLeft(hasText(COB_TEXT)) - placedLeft(hasContentDescription(cobLabel))

    /**
     * Left edge of the placed node. When the icons do not fit, the row subcomposes the chips twice
     * (with icons to measure, without to place) and the unused slot leaves a zero-size twin behind.
     */
    private fun placedLeft(matcher: SemanticsMatcher): Dp = with(compose.density) { placedBounds(matcher).left.toDp() }

    private fun placedWidth(matcher: SemanticsMatcher): Dp = with(compose.density) { placedBounds(matcher).width.toDp() }

    private fun placedBounds(matcher: SemanticsMatcher): Rect =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
            .single { it.boundsInRoot.width > 0f }
            .boundsInRoot

    private fun setRow(carbsReq: Int, width: Dp = NARROW_ROW) {
        compose.setContent {
            MaterialTheme {
                // NARROW_ROW is the portrait layout next to the BG circle: wide enough for the
                // texts, not for the icons as well. WIDE_ROW fits both chips with their texts.
                Box(Modifier.width(width)) {
                    IobCobChipsRow(
                        iobUiState = IobUiState(text = "2.66 U", iobTotal = 2.66),
                        cobUiState = CobUiState(text = COB_TEXT, carbsReq = carbsReq, cobValue = 43.0),
                        onIobChipClick = {}
                    )
                }
            }
        }
    }

    private companion object {

        const val COB_TEXT = "43 g(6) 16req"
        val NARROW_ROW = 200.dp
        val WIDE_ROW = 360.dp
    }
}
