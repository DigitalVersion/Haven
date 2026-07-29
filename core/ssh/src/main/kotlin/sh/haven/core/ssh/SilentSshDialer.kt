package sh.haven.core.ssh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.fido.FidoAuthenticator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SilentSshDialer @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val hostKeyVerifier: HostKeyVerifier,
    private val connectionLogRepository: ConnectionLogRepository,
    private val sshKeyResolver: SshKeyResolver,
) {
    companion object {
        private const val TAG = "SilentSshDialer"
    }

    suspend fun dialFilesSession(
        profile: ConnectionProfile,
        fidoAuthenticator: FidoAuthenticator? = null,
        verboseLogger: SshVerboseLogger? = null,
    ): String {
        val password = profile.sshPassword ?: ""
        val client = SshConnectionFactory.create(sshEngineFromOptionsText(profile.sshOptions)).apply {
            this.fidoAuthenticator = fidoAuthenticator
            this.verboseLogger = verboseLogger
        }
        
        val sessionId = sshSessionManager.registerSession(
            profileId = profile.id,
            label = profile.label,
            client = client,
            headless = true, // FILES session is headless (no shell)
            purpose = SshSessionManager.SessionState.SessionPurpose.FILES
        )

        try {
            withContext(Dispatchers.IO) {
                val authMethod = sshKeyResolver.resolveAuthMethod(profile, password)
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = authMethod,
                    sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                    forwardAgent = profile.forwardAgent,
                    remoteCommand = null, // FILES channel has no remote command
                    requestPty = false,    // FILES channel does not request PTY
                    addressFamily = ConnectionConfig.AddressFamily.valueOf(profile.addressFamilyEnum.name),
                    agentIdentities = sshKeyResolver.agentIdentitiesFor(profile),
                )
                
                // JSch silent connect with no Haven proxy in v1 (mục 9.2)
                val hostKeyEntry = client.connect(
                    config,
                    proxy = null,
                    preConnect = null,
                    trustedHostCaKeys = hostKeyVerifier.trustedHostCaKeys(),
                )

                // Verify host key (silent verification must be fail-closed)
                val result = if (hostKeyEntry == null) HostKeyResult.Trusted else hostKeyVerifier.verify(hostKeyEntry)
                when (result) {
                    is HostKeyResult.Trusted -> {}
                    is HostKeyResult.NewHost -> {
                        client.disconnect()
                        throw Exception(
                            "Unknown host key for ${profile.host} — open this connection from " +
                                "the Connections tab first to verify and trust its host key.",
                        )
                    }
                    is HostKeyResult.KeyChanged -> {
                        client.disconnect()
                        throw Exception("Host key changed for ${profile.host} — possible MITM")
                    }
                }

                // Since we don't open a shell channel (headless=true), we just store config and update status to CONNECTED
                sshSessionManager.storeConnectionConfig(
                    sessionId = sessionId,
                    config = config,
                    sessionMgr = SessionManager.NONE,
                    sessionCommandOverride = null,
                    remoteCommand = null,
                    requestPty = false,
                )
            }
            sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)
            return sessionId
        } catch (e: Exception) {
            Log.e(TAG, "dialFilesSession failed for ${profile.label}: ${e.message}", e)
            connectionLogRepository.logEvent(
                profile.id,
                ConnectionLog.Status.FAILED,
                details = "Files channel: " + e.message,
                verboseLog = verboseLogger?.drain()
            )
            sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.ERROR)
            sshSessionManager.removeSession(sessionId)
            throw e
        }
    }
}
