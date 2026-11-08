package app.aaps.ui.compose.overview.chips

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.core.ui.compose.stringResource

/** Minimal I:C dialog: today, profile, and the difference with its percentage. */
@Composable
fun IcInfoDialog(
    state: IcInfoUiState,
    onDismiss: () -> Unit
) {
    val title = stringResource(CoreUiStrings.ic_estimate_title)
    if (state.unavailable) {
        OkDialog(
            title = title,
            message = stringResource(CoreUiStrings.ic_estimate_unavailable),
            onDismiss = onDismiss
        )
        return
    }
    val differenceColor = when (state.direction) {
        IcDiffDirection.UP   -> AapsTheme.generalColors.statusNormal
        IcDiffDirection.DOWN -> AapsTheme.generalColors.statusCritical
        IcDiffDirection.SAME -> MaterialTheme.colorScheme.onSurface
    }
    val todayText = stringResource(CoreUiStrings.ic_estimate_today, state.today)
    val profileText = stringResource(CoreUiStrings.ic_estimate_profile, state.profile)
    val message = buildAnnotatedString {
        append(todayText)
        append("\n")
        append(profileText)
        state.difference?.let {
            append("\n")
            withStyle(SpanStyle(color = differenceColor)) { append(it) }
        }
    }
    OkDialog(
        title = title,
        message = message,
        onDismiss = onDismiss
    )
}
