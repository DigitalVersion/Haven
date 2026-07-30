package sh.haven.app.backup

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import sh.haven.core.data.backup.RemoteBackupIo
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.ssh.SilentSshDialer
import sh.haven.feature.sftp.transport.FileBackend
import sh.haven.feature.sftp.transport.TransportSelector
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TransportBackupIo"

/**
 * App-layer [RemoteBackupIo] over `feature/sftp`'s [TransportSelector] — the
 * same seam `MailToolProvider` uses to write attachments to any connected
 * filesystem (#323). Lives in the app module because that's where the
 * `feature/sftp` dependency belongs; `core/data` and `feature/settings` see
 * only the interface.
 *
 * Backup sync (manual or auto-pull) is a headless/background action — there's
 * no UI screen the user opened first to establish the connection, unlike
 * Files-tab browsing. So an SSH-backed destination that isn't already
 * connected gets a silent Files-purpose dial (same mechanism the Connections
 * screen's Files icon uses) before giving up. SMB/rclone/local/SAF backends
 * still require an existing connection — [SilentSshDialer] only dials SSH.
 */
@Singleton
class TransportBackupIo @Inject constructor(
    private val transportSelector: TransportSelector,
    private val connectionRepository: ConnectionRepository,
    private val silentSshDialer: SilentSshDialer,
) : RemoteBackupIo {

    override suspend fun writeBackup(profileId: String, remotePath: String, data: ByteArray) {
        backend(profileId).writeBytes(remotePath, data)
    }

    override suspend fun readBackup(profileId: String, remotePath: String): ByteArray =
        backend(profileId).readBytes(remotePath)

    private suspend fun backend(profileId: String): FileBackend {
        transportSelector.resolveFileBackend(profileId)?.let { return it.backend }

        val profile = connectionRepository.getById(profileId)
        if (profile != null) {
            try {
                silentSshDialer.dialFilesSession(profile)
            } catch (e: Exception) {
                Log.w(TAG, "silent dial failed for backup destination $profileId", e)
                throw IllegalStateException(
                    "Couldn't connect to backup destination '${profile.label}': ${e.message}",
                )
            }
            transportSelector.resolveFileBackend(profileId)?.let { return it.backend }
        }

        throw IllegalStateException(
            "Backup destination '$profileId' isn't connected — open it first, then retry.",
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupIoModule {
    @Binds
    abstract fun bindRemoteBackupIo(impl: TransportBackupIo): RemoteBackupIo
}
