package app.aaps.plugins.sync.healthconnect

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.health.connect.client.PermissionController
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import javax.inject.Inject

/**
 * Rationale screen for Health Connect permissions.
 *
 * Owns the ActivityResultLauncher for the permission request contract and is the
 * target of the ViewPermissionUsageActivity alias, which Health Connect launches
 * when the user reviews permissions on API < 34.
 */
class HealthConnectPermissionsRationaleActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var healthConnectPlugin: HealthConnectPlugin

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectPlugin.REQUIRED_PERMISSIONS)) finish()
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
        launchPermissionRequest()
    }

    private fun launchPermissionRequest() {
        if (healthConnectPlugin.isAvailable) {
            requestPermissions.launch(HealthConnectPlugin.REQUIRED_PERMISSIONS)
        }
    }
}
