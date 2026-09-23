package app.aaps.plugins.sync.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.healthconnect.keys.HealthConnectBooleanKey
import app.aaps.plugins.sync.healthconnect.keys.HealthConnectLongNonKey
import app.aaps.plugins.sync.healthconnect.keys.HealthConnectStringNonKey
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.rx3.rxSingle
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Reads heart rate and steps from Android Health Connect, as an alternative to the
 * AAPS Wear app. Data is stored in the same HR/SC tables the Wear path uses, so the
 * Overview graph and automation triggers work unchanged.
 */
@Singleton
class HealthConnectPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    private val context: Context,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val rxBus: RxBus,
    private val aapsSchedulers: AapsSchedulers
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.SYNC)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_watch)
        .pluginName(R.string.healthconnect)
        .shortName(R.string.healthconnect_shortname)
        .description(R.string.healthconnect_description)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN),
    ownPreferences = listOf(HealthConnectBooleanKey::class.java, HealthConnectLongNonKey::class.java),
    aapsLogger, rh, preferences
) {

    companion object {

        const val DEVICE_NAME = "Health Connect"
        private const val WORK_NAME_PERIODIC = "HealthConnectPeriodic"
        private const val WORK_NAME_MANUAL = "HealthConnectManual"
        private val READ_WINDOW = T.hours(3).msecs()
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class)
        )
    }

    private val disposable = CompositeDisposable()

    /** UI listeners notified when the granted permission set changes. */
    private val permissionsListeners = ConcurrentHashMap<Any, (Boolean) -> Unit>()

    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    val isHealthConnectEnabled: Boolean
        get() = isAvailable && preferences.get(HealthConnectBooleanKey.UseHealthConnect)

    /** Registers a listener, invoked immediately with the current state and on every change. */
    fun addPermissionsListener(owner: Any, listener: (Boolean) -> Unit) {
        permissionsListeners[owner] = listener
        checkPermissions()
    }

    fun removePermissionsListener(owner: Any) {
        permissionsListeners.remove(owner)
    }

    /** Checks granted permissions asynchronously and notifies listeners with the result. */
    fun checkPermissions() {
        rxSingle { hasAllPermissions() }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ granted -> permissionsListeners.values.forEach { it(granted) } }, { })
            .let(disposable::add)
    }

    /** Permissions granted so far (empty set if HC unavailable). */
    suspend fun grantedPermissions(): Set<String> =
        if (!isAvailable) emptySet()
        else try {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            aapsLogger.error(LTag.HEALTHCONNECT, "Failed reading granted permissions", e)
            emptySet()
        }

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(REQUIRED_PERMISSIONS.map { it })

    override fun onStart() {
        super.onStart()
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .subscribe({ event ->
                           if (event.isChanged(HealthConnectBooleanKey.UseHealthConnect.key)) onEnabledChanged()
                       }, { })
        onEnabledChanged()
    }

    override fun onStop() {
        disposable.clear()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        super.onStop()
    }

    private fun onEnabledChanged() {
        if (isHealthConnectEnabled) {
            aapsLogger.debug(LTag.HEALTHCONNECT, "Health Connect integration enabled - scheduling reads")
            schedulePeriodicRead()
            readNow()
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        }
    }

    fun schedulePeriodicRead() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HealthConnectWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    fun readNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_MANUAL,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HealthConnectWorker>().build()
        )
    }

    /**
     * Reads HR + steps records from Health Connect and stores them.
     *
     * Uses a changes token for incremental reads (only new/updated records are fetched).
     * Falls back to a full read of the last [READ_WINDOW] when no token exists yet or the
     * token has expired (e.g. after Health Connect data deletion).
     */
    suspend fun readAndStore() {
        if (!isHealthConnectEnabled) return
        if (!hasAllPermissions()) {
            aapsLogger.debug(LTag.HEALTHCONNECT, "Permissions not granted - skipping read")
            return
        }
        val client = HealthConnectClient.getOrCreate(context)
        val token = preferences.get(HealthConnectStringNonKey.ChangesToken)

        try {
            if (token.isNotEmpty()) {
                readChanges(client, token)
            } else {
                readFullWindow(client)
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.HEALTHCONNECT, "Health Connect read failed", e)
        }
    }

    /** Incremental read via changes token; falls back to a full read when the token expired. */
    private suspend fun readChanges(client: HealthConnectClient, token: String) {
        var hrCount = 0
        val changedSteps = mutableListOf<StepsRecord>()
        var currentToken = token
        var expired = false
        try {
            do {
                val response = client.getChanges(currentToken)
                for (change in response.changes) {
                    if (change is UpsertionChange) {
                        when (val record = change.record) {
                            is HeartRateRecord -> { storeHeartRate(listOf(record)); hrCount++ }
                            is StepsRecord     -> changedSteps.add(record)
                        }
                    }
                    // DeletionChange: records we already imported are never deleted by us;
                    // deletions in HC (e.g. source app removed data) are ignored.
                }
                currentToken = response.nextChangesToken
            } while (response.hasMore)
            if (changedSteps.isNotEmpty()) rebuildSteps(client, changedSteps)
        } catch (e: Exception) {
            // A stale token (data cleared, permission re-grant) surfaces as an exception -
            // fall back to a full window read.
            aapsLogger.debug(LTag.HEALTHCONNECT, "Changes read failed - falling back to full read", e)
            expired = true
        }
        if (expired) {
            preferences.put(HealthConnectStringNonKey.ChangesToken, "")
            readFullWindow(client)
            return
        }
        preferences.put(HealthConnectStringNonKey.ChangesToken, currentToken)
        aapsLogger.debug(LTag.HEALTHCONNECT, "Changes read: $hrCount HR and ${changedSteps.size} steps records")
    }

    /**
     * Rebuilds SC records for the buckets touched by the given steps records. The rolling
     * 10/15/30/60/180-min windows only look backward, so every bucket from the first touched
     * one onward is re-stored.
     */
    private suspend fun rebuildSteps(client: HealthConnectClient, changed: List<StepsRecord>) {
        val earliest = changed.minOf { it.startTime.toEpochMilli() }
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(earliest - T.hours(3).msecs()), Instant.ofEpochMilli(dateUtil.now()))
        val response = client.readRecords(
            ReadRecordsRequest(recordType = StepsRecord::class, timeRangeFilter = range)
        )
        val fromBucket = earliest / T.mins(5).msecs() * T.mins(5).msecs() + T.mins(5).msecs()
        storeSteps(response.records, fromBucket = fromBucket)
    }

    /** Full read of the last [READ_WINDOW], then obtains a changes token for future incremental reads. */
    private suspend fun readFullWindow(client: HealthConnectClient) {
        val from = max(preferences.get(HealthConnectLongNonKey.LastRead), dateUtil.now() - READ_WINDOW)
        val to = dateUtil.now()
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))

        val hrResponse = client.readRecords(
            ReadRecordsRequest(recordType = HeartRateRecord::class, timeRangeFilter = range)
        )
        storeHeartRate(hrResponse.records)

        val stepsResponse = client.readRecords(
            ReadRecordsRequest(recordType = StepsRecord::class, timeRangeFilter = range)
        )
        storeSteps(stepsResponse.records)

        preferences.put(HealthConnectLongNonKey.LastRead, to)
        preferences.put(
            HealthConnectStringNonKey.ChangesToken,
            client.getChangesToken(ChangesTokenRequest(recordTypes = setOf(HeartRateRecord::class, StepsRecord::class)))
        )
        aapsLogger.debug(LTag.HEALTHCONNECT, "Full read: ${hrResponse.records.size} HR and ${stepsResponse.records.size} steps records")
    }

    /**
     * Stores HR samples from the given records.
     *
     * HC samples are instants, but HR consumers expect a sampling duration (the graph draws
     * each sample as a bar of that width and automation averages weighted by duration), so a
     * duration is derived per sample from the gap to the next sample, falling back to the
     * record span and finally to one minute.
     */
    private fun storeHeartRate(records: List<HeartRateRecord>) {
        val defaultDuration = T.mins(1).msecs()
        for (record in records) {
            val samples = record.samples
            for ((index, sample) in samples.withIndex()) {
                val bpm = sample.beatsPerMinute ?: continue
                val ts = sample.time.toEpochMilli()
                val nextTs = samples.getOrNull(index + 1)?.time?.toEpochMilli() ?: 0L
                val duration = when {
                    nextTs > ts                    -> nextTs - ts
                    record.endTime > record.startTime -> (record.endTime.toEpochMilli() - ts).coerceAtLeast(defaultDuration)
                    else                           -> defaultDuration
                }
                val hr = HR(
                    timestamp = ts,
                    duration = duration,
                    beatsPerMinute = bpm.toDouble(),
                    device = DEVICE_NAME
                )
                // reuse the existing row for this timestamp to avoid duplicates on re-import
                val existing = persistenceLayer.getHeartRatesFromTimeToTime(ts, ts)
                    .firstOrNull { it.device == DEVICE_NAME }
                if (existing != null) hr.id = existing.id
                persistenceLayer.insertOrUpdateHeartRate(hr).subscribe()
            }
        }
    }

    /**
     * Builds SC records with 5/10/15/30/60/180-min rolling windows from raw steps records.
     * When [fromBucket] is non-null, only buckets at or after it are stored (incremental
     * rebuild); otherwise all buckets in the batch are stored (full read).
     */
    private fun storeSteps(records: List<StepsRecord>, fromBucket: Long? = null) {
        // flatten to (bucketEnd, steps) per 5-min bucket
        val buckets = LinkedHashMap<Long, Int>()
        for (record in records) {
            val start = record.startTime.toEpochMilli()
            val end = record.endTime.toEpochMilli()
            if (end <= start) continue
            // first bucket boundary at or after start; a record ending exactly on a boundary
            // belongs to the bucket ending there
            var bucketEnd = (start / T.mins(5).msecs() + 1) * T.mins(5).msecs()
            while (bucketEnd <= end) {
                val overlapStart = max(start, bucketEnd - T.mins(5).msecs())
                val overlapEnd = min(end, bucketEnd)
                if (overlapEnd > overlapStart) {
                    val fraction = (overlapEnd - overlapStart).toDouble() / (end - start)
                    buckets[bucketEnd] = (buckets.getOrDefault(bucketEnd, 0) + record.count * fraction).toInt()
                }
                bucketEnd += T.mins(5).msecs()
            }
        }
        val sortedBuckets = buckets.toSortedMap()
        for ((bucketEnd, steps5min) in sortedBuckets) {
            if (fromBucket != null && bucketEnd < fromBucket) continue
            var steps10 = 0
            var steps15 = 0
            var steps30 = 0
            var steps60 = 0
            var steps180 = 0
            for (window in 1..36) {
                val b = sortedBuckets[bucketEnd - window * T.mins(5).msecs()] ?: continue
                when {
                    window <= 2  -> steps10 += b
                    window <= 3  -> steps15 += b
                    window <= 6  -> steps30 += b
                    window <= 12 -> steps60 += b
                    window <= 36 -> steps180 += b
                }
            }
            val sc = SC(
                duration = T.mins(5).msecs(),
                timestamp = bucketEnd,
                steps5min = steps5min,
                steps10min = steps10,
                steps15min = steps15,
                steps30min = steps30,
                steps60min = steps60,
                steps180min = steps180,
                device = DEVICE_NAME
            )
            // reuse the existing row for this bucket to avoid duplicates on re-import
            val existing = persistenceLayer.getStepsCountFromTimeToTime(bucketEnd, bucketEnd)
                .firstOrNull { it.device == DEVICE_NAME }
            if (existing != null) sc.id = existing.id
            persistenceLayer.insertOrUpdateStepsCount(sc).subscribe()
        }
    }

    override fun addPreferenceScreen(preferenceManager: androidx.preference.PreferenceManager, parent: androidx.preference.PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = androidx.preference.PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "healthconnect_settings"
            title = rh.gs(R.string.healthconnect)
            initialExpandedChildrenCount = 0
            addPreference(
                HealthConnectSwitchPreference(context, this@HealthConnectPlugin)
            )
        }
    }
}
