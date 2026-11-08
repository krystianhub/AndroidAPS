package app.aaps.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.pump.InjectionPosition
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * MDI injection-site row: the previous site is shown as a disabled field on the left, so it is
 * visible at a glance without becoming editable. The active site is the normal number picker.
 */
@Composable
fun InjectionPositionRow(
    position: Int,
    lastPosition: Int?,
    onPositionChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = lastPosition?.toString() ?: "-",
            onValueChange = { },
            enabled = false,
            singleLine = true,
            label = { Text(stringResource(UiStrings.position_previous_label)) },
            // Two digits at most; the wide field made the active picker look like an afterthought.
            modifier = Modifier.width(88.dp)
        )
        Box(modifier = Modifier.weight(1f)) {
            NumberInputRow(
                labelRef = InterfacesStrings.position_label,
                value = position.toDouble(),
                onValueChange = { onPositionChange(it.toInt()) },
                valueRange = 1.0..InjectionPosition.MAX_POSITION.toDouble(),
                step = 1.0,
                unitLabel = TextRef.Literal(""),
                // The "1 — 12" line under the picker made it taller than the Previous field.
                compact = true
            )
        }
    }
}
