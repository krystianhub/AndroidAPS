package app.aaps.plugins.sync.healthconnect

import android.content.Context
import android.content.Intent
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.healthconnect.keys.HealthConnectBooleanKey

/**
 * "Use Health Connect" switch that also handles permissions:
 *
 * - Permissions missing: the summary says so and tapping launches the Health Connect
 *   permission request instead of toggling; the switch stays off until they are granted.
 * - Permissions granted: the switch toggles the integration on/off as usual.
 *
 * The permission request is launched through [HealthConnectPermissionsRationaleActivity],
 * which owns the ActivityResultLauncher for the permission contract (and doubles as the
 * ViewPermissionUsageActivity rationale target required by Health Connect on API < 34).
 */
class HealthConnectSwitchPreference(
    ctx: Context,
    private val healthConnectPlugin: HealthConnectPlugin
) : AdaptiveSwitchPreference(ctx, booleanKey = HealthConnectBooleanKey.UseHealthConnect, title = R.string.healthconnect_use) {

    private var permissionsMissing = true

    init {
        setSummary(R.string.healthconnect_permissions_missing)
    }

    override fun onAttached() {
        super.onAttached()
        healthConnectPlugin.addPermissionsListener(this) { granted ->
            permissionsMissing = !granted
            setSummary(if (granted) R.string.healthconnect_permissions_granted
                       else R.string.healthconnect_permissions_missing)
        }
    }

    override fun onDetached() {
        healthConnectPlugin.removePermissionsListener(this)
        super.onDetached()
    }

    override fun onClick() {
        if (permissionsMissing) {
            context.startActivity(Intent(context, HealthConnectPermissionsRationaleActivity::class.java))
        } else {
            super.onClick()
        }
    }
}
