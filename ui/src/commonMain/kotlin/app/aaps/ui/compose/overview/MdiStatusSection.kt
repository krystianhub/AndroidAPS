package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.overview.statusLights.StatusUiState

/** MDI fork: pen status row - last bolus, last basal, insulinReq and the predicted BG. */
@Composable
fun MdiStatusSection(
    state: StatusUiState,
    modifier: Modifier = Modifier
) {
    if (!state.isMdi) return
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            MdiRow(
                label = stringResource(CoreUiStrings.statuslights_last_bolus_age),
                value = listOfNotNull(state.lastBolusAge, state.lastBolusDose).joinToString(" / "),
                level = state.lastBolusLevel
            )
            MdiRow(
                label = stringResource(CoreUiStrings.statuslights_last_basal_age),
                value = listOfNotNull(state.lastBasalAge, state.lastBasalDose).joinToString(" / "),
                level = state.lastBasalLevel
            )
            MdiRow(
                label = stringResource(CoreUiStrings.statuslights_insulin_req),
                value = listOfNotNull(state.insulinReq, state.insulinReqMedian).joinToString(" / "),
                level = StatusLevel.UNSPECIFIED
            )
            MdiRow(
                label = stringResource(CoreUiStrings.statuslights_eventual_bg),
                value = state.predictedBg ?: "-",
                level = state.predictedBgLevel
            )
        }
    }
}

@Composable
private fun MdiRow(label: String, value: String, level: StatusLevel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = statusLevelToColor(level)
        )
    }
}
