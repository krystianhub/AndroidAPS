package app.aaps.plugins.sync.healthconnect

import android.os.Bundle
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity

/**
 * Simple rationale screen shown by Health Connect when the user reviews permissions
 * (required intent target for androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE).
 */
class HealthConnectPermissionsRationaleActivity : TranslatedDaggerAppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(app.aaps.plugins.sync.R.string.healthconnect)
        setContentView(android.widget.TextView(this).apply {
            setPadding(48, 48, 48, 48)
            text = context.getString(app.aaps.plugins.sync.R.string.healthconnect_use_summary)
        })
    }
}
