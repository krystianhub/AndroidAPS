package app.aaps.plugins.sync.healthconnect

import android.content.Context
import androidx.work.WorkerParameters
import app.aaps.core.objects.workflow.LoggingWorker
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/** Periodically reads heart rate + steps from Health Connect into the AAPS database. */
class HealthConnectWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.IO) {

    @Inject lateinit var healthConnectPlugin: HealthConnectPlugin

    override suspend fun doWorkAndLog(): Result {
        healthConnectPlugin.readAndStore()
        return Result.success()
    }
}
