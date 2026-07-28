package sh.haven.app.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.haven.core.data.backup.BackupSyncManager
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatic backup pull from the configured remote (#359 follow-up).
 *
 * Mirror structure of BackupAutoSyncWorker but performs a backup pull/restore.
 */
@HiltWorker
class BackupAutoPullWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val preferencesRepository: UserPreferencesRepository,
    private val backupSyncManager: BackupSyncManager,
    private val connectionLogRepository: ConnectionLogRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!preferencesRepository.backupAutoPullEnabled.first()) return Result.success()
        val profileId = preferencesRepository.backupSyncProfileId.first() ?: return Result.success()
        val passphrase = preferencesRepository.backupSyncPassphrase() ?: return Result.success()
        val path = preferencesRepository.backupSyncPath.first()
        return try {
            val started = System.currentTimeMillis()
            val result = backupSyncManager.pull(profileId, path, passphrase)
            val msg = "Restored ${result.count} items" +
                if (result.errors.isNotEmpty()) " (${result.errors.size} errors)" else ""
            connectionLogRepository.logEvent(
                profileId, ConnectionLog.Status.SYNC_OK,
                durationMs = System.currentTimeMillis() - started,
                details = "Auto-pull backup ← $path: $msg",
            )
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "auto-pull failed (attempt $runAttemptCount)", e)
            if (runAttemptCount >= MAX_ATTEMPTS) {
                connectionLogRepository.logEvent(
                    profileId, ConnectionLog.Status.SYNC_FAILED,
                    details = "Auto-pull backup failed: ${e.message ?: e.javaClass.simpleName}",
                )
                Result.failure()
            } else Result.retry()
        }
    }

    companion object {
        private const val TAG = "BackupAutoPull"
        private const val UNIQUE_PERIODIC = "backup-auto-pull-periodic"
        private const val MAX_ATTEMPTS = 5

        private val networked = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Daily periodic pull. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupAutoPullWorker>(24, TimeUnit.HOURS)
                .setConstraints(networked)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(UNIQUE_PERIODIC)
            }
        }
    }
}

@Singleton
class BackupAutoPullScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: UserPreferencesRepository,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            preferencesRepository.backupAutoPullEnabled.distinctUntilChanged().collect { enabled ->
                if (!enabled) {
                    BackupAutoPullWorker.cancelAll(context)
                    return@collect
                }
                BackupAutoPullWorker.schedulePeriodic(context)
            }
        }
    }
}
