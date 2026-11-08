package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import app.aaps.core.ui.compose.AapsSpacing

/**
 * @see IobCobChipsRowPreview
 * @see IobCobChipsRowCarbsReqPreview
 */
@Composable
fun IobCobChipsRow(
    iobUiState: IobUiState,
    cobUiState: CobUiState,
    onIobChipClick: (() -> Unit)? = null,
    onCobChipClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val spacingDp = AapsSpacing.small
    SubcomposeLayout(
        modifier = modifier.fillMaxWidth()
    ) { constraints ->
        val spacingPx = spacingDp.roundToPx()
        val isWidthBounded = constraints.hasBoundedWidth
        val availableWidth = if (isWidthBounded) (constraints.maxWidth - spacingPx).coerceAtLeast(0) else 0

        // First pass: measure intrinsic widths with icons
        val withIcons = subcompose("withIcons") {
            IobChip(state = iobUiState, onClick = onIobChipClick, showIcon = true)
            CobChip(state = cobUiState, showIcon = true, onClick = onCobChipClick)
        }
        val intrinsicsWithIcons = withIcons.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val totalWithIcons = intrinsicsWithIcons.sum()

        // If chips with icons don't fit, hide icons to free up space. Not while carbs are required:
        // the blinking COB icon is the cue, and the "16req" text is what made the row too narrow in
        // the first place, so the cue would vanish exactly when it is needed. The COB text scrolls
        // instead (see CobChip).
        val showIcons = cobUiState.carbsReq > 0 || !isWidthBounded || totalWithIcons <= availableWidth

        val measurables = if (showIcons) {
            withIcons
        } else {
            subcompose("withoutIcons") {
                IobChip(state = iobUiState, onClick = onIobChipClick, showIcon = false)
                CobChip(state = cobUiState, showIcon = false, onClick = onCobChipClick)
            }
        }

        // Max intrinsic width = the width each chip needs to render on a single line.
        // (min intrinsic only guarantees the longest word fits, which wraps IOB.)
        val naturalWidths = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }

        val placeables = if (isWidthBounded) {
            // Without a request both chips share the row equally. With "N req" the COB text is
            // longer, so use the upstream split: IOB hugs its own text and COB takes the rest.
            val widths = if (cobUiState.carbsReq > 0) {
                val iobWidth = naturalWidths[0].coerceAtMost(availableWidth / 2)
                listOf(iobWidth, (availableWidth - iobWidth).coerceAtLeast(0))
            } else {
                listOf(availableWidth / 2, availableWidth / 2)
            }
            listOf(
                measurables[0].measure(constraints.copy(minWidth = widths[0], maxWidth = widths[0])),
                measurables[1].measure(constraints.copy(minWidth = widths[1], maxWidth = widths[1]))
            )
        } else {
            measurables.mapIndexed { i, measurable ->
                measurable.measure(constraints.copy(minWidth = naturalWidths[i], maxWidth = naturalWidths[i]))
            }
        }

        val height = if (placeables.isEmpty()) 0 else placeables.maxOf { it.height }
        val layoutWidth = if (isWidthBounded) constraints.maxWidth else placeables.sumOf { it.width } + (if (placeables.size > 1) (placeables.size - 1) * spacingPx else 0)
        layout(layoutWidth, height) {
            var x = 0
            placeables.forEachIndexed { i, placeable ->
                placeable.place(x, 0)
                x += placeable.width + if (i < placeables.lastIndex) spacingPx else 0
            }
        }
    }
}
