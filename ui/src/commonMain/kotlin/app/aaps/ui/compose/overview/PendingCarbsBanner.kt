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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource

/** MDI fork: the deferred carb entry waiting for "Eat now", with the one-shot expiry notice. */
@Composable
fun PendingCarbsBanner(
    pending: Boolean,
    expired: Boolean,
    onEatNow: () -> Unit,
    onCancel: () -> Unit,
    onDismissExpired: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!pending && !expired) return
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (pending) Text(
                    text = stringResource(CoreUiStrings.pending_carbs_title),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (expired) Text(
                    text = stringResource(CoreUiStrings.pending_carbs_expired),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (pending) {
                TextButton(onClick = onEatNow) { Text(stringResource(CoreUiStrings.eat_now)) }
                TextButton(onClick = onCancel) { Text(stringResource(CoreUiStrings.cancel)) }
            } else {
                TextButton(onClick = onDismissExpired) { Text(stringResource(CoreUiStrings.ok)) }
            }
        }
    }
}
