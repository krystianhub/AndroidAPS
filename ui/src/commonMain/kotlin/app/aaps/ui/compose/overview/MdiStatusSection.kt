package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.icons.IcBolus
import app.aaps.core.ui.compose.icons.IcMdi
import app.aaps.core.ui.compose.icons.IcStats
import app.aaps.core.ui.compose.icons.IcTarget
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.overview.statusLights.StatusUiState

/**
 * MDI fork: the compact pen status card - last bolus, last basal, suggested insulin and the
 * predicted BG. Same information as the old status-light rows, in one card.
 */
@Composable
fun MdiStatusSection(
    state: StatusUiState,
    modifier: Modifier = Modifier
) {
    if (!state.isMdi) return

    AapsCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MdiLight(
                    icon = IcBolus,
                    label = stringResource(CoreUiStrings.statuslights_last_bolus_age),
                    value = listOfNotNull(state.lastBolusAge, state.lastBolusDose).joinToString(" / "),
                    level = state.lastBolusLevel,
                    valueColor = storageColor(state.lastBolusLevel),
                    modifier = Modifier.weight(1f)
                )
                MdiLight(
                    icon = IcMdi,
                    label = stringResource(CoreUiStrings.statuslights_last_basal_age),
                    value = listOfNotNull(state.lastBasalAge, state.lastBasalDose).joinToString(" / "),
                    level = state.lastBasalLevel,
                    valueColor = storageColor(state.lastBasalLevel),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MdiLight(
                    icon = IcStats,
                    label = stringResource(CoreUiStrings.statuslights_insulin_req),
                    value = listOfNotNull(state.insulinReq, state.insulinReqMedian).joinToString(" / "),
                    level = StatusLevel.UNSPECIFIED,
                    stale = state.apsStale,
                    modifier = Modifier.weight(1f)
                )
                MdiLight(
                    icon = IcTarget,
                    label = stringResource(CoreUiStrings.statuslights_eventual_bg),
                    value = state.predictedBg.orEmpty(),
                    level = state.predictedBgLevel,
                    valueColor = storageColor(state.predictedBgLevel),
                    stale = state.apsStale,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Green means the dose is in its plateau; the ordinary NORMAL status is body text elsewhere. */
@Composable
private fun storageColor(level: StatusLevel): Color = when (level) {
    StatusLevel.NORMAL      -> AapsTheme.generalColors.statusNormal
    StatusLevel.WARNING     -> AapsTheme.generalColors.statusWarning
    StatusLevel.CRITICAL    -> AapsTheme.generalColors.statusCritical
    StatusLevel.UNSPECIFIED -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun MdiLight(
    icon: ImageVector,
    label: String,
    value: String,
    level: StatusLevel,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    stale: Boolean = false
) {
    val staleValue = stale && value.isNotEmpty() && value != "-"
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value.ifEmpty { "-" },
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor ?: statusLevelToColor(level),
                // Same cue as pre-4.0: an APS result older than 5 min is dimmed and struck through.
                textDecoration = if (staleValue) TextDecoration.LineThrough else null,
                modifier = if (staleValue) Modifier.alpha(0.45f) else Modifier
            )
        }
    }
}
