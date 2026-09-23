package app.aaps.plugins.sync.healthconnect

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts the ActivityResultLauncher for the Health Connect permission request contract.
 *
 * Opened from [HealthConnectSwitchPreference] when permissions are missing, and also
 * launched by Health Connect itself as the ViewPermissionUsageActivity rationale target
 * on API < 34 (via the activity-alias in the manifest).
 */
class HealthConnectPermissionsRationaleActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var healthConnectPlugin: HealthConnectPlugin

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectPlugin.REQUIRED_PERMISSIONS)) {
            healthConnectPlugin.checkPermissions()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(app.aaps.plugins.sync.R.string.healthconnect)
        val pad = (resources.displayMetrics.density * 16).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply {
            text = context.getString(app.aaps.plugins.sync.R.string.healthconnect_use_summary)
        })
        layout.addView(Button(this).apply {
            setText(app.aaps.plugins.sync.R.string.healthconnect_grant_permissions)
            setOnClickListener { launchPermissionRequest() }
        })
        setContentView(layout)
        // Auto-launch only when permissions are missing; when Health Connect opens this as
        // the rationale screen with everything granted, the request would return instantly.
        lifecycleScope.launch {
            if (!healthConnectPlugin.hasAllPermissions()) launchPermissionRequest()
        }
    }

    private fun launchPermissionRequest() {
        if (healthConnectPlugin.isAvailable) {
            requestPermissions.launch(HealthConnectPlugin.REQUIRED_PERMISSIONS)
        }
    }
}
