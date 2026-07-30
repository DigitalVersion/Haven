package sh.haven.feature.connections

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.data.repository.TotpSecretRepository
import sh.haven.core.stepca.CertRenewalGate
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.SshKeyResolver
import sh.haven.core.ssh.SshKeyExporter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshKeyResolverImpl @Inject constructor(
    private val sshKeyRepository: SshKeyRepository,
    private val certRenewalGate: CertRenewalGate,
) : SshKeyResolver {

    companion object {
        private const val TAG = "SshKeyResolverImpl"
    }

    override suspend fun resolveAuthMethod(
        profile: ConnectionProfile,
        password: String
    ): ConnectionConfig.AuthMethod {
        if (profile.ignoreSavedKeys) {
            return ConnectionConfig.AuthMethod.Password(password)
        }

        // Profile has an explicit key assigned
        val keyId = profile.keyId
        if (keyId != null) {
            resolveExplicitKey(keyId, password)?.let { return it }
        }

        // No explicit key but keys are available — try every key the
        // server might accept.
        if (password.isEmpty()) {
            resolveAnyUsableKeys()?.let { return it }
        }

        return ConnectionConfig.AuthMethod.Password(password)
    }

    override suspend fun agentIdentitiesFor(profile: ConnectionProfile): List<ConnectionConfig.AgentIdentity> {
        if (!profile.forwardAgent) {
            return emptyList()
        }
        val allKeys = sshKeyRepository.getAllDecrypted()
        val candidates = allKeys.filter { !it.keyType.startsWith("sk-") && it.enabledForAuth }
        val keys = mutableListOf<ConnectionConfig.AgentIdentity>()
        for (key in candidates) {
            if (!key.isEncrypted) {
                keys += ConnectionConfig.AgentIdentity(key.label, SshKeyExporter.toPem(key.privateKeyBytes, key.keyType))
                continue
            }
            val passphrase = sshKeyRepository.getStoredPassphrase(key.id)
            if (passphrase != null) {
                keys += ConnectionConfig.AgentIdentity(
                    key.label,
                    key.privateKeyBytes,
                    passphrase.toByteArray(Charsets.UTF_8),
                )
            }
        }
        return keys
    }

    private suspend fun resolveExplicitKey(
        keyId: String,
        password: String,
    ): ConnectionConfig.AuthMethod? {
        val originalKey = sshKeyRepository.getById(keyId)
        val key = if (originalKey != null) certRenewalGate.ensureFresh(originalKey) else null
        val keyBytes = if (key != null) sshKeyRepository.getDecryptedKeyBytes(keyId) else null
        if (keyBytes != null && key != null) {
            val certBytes = sshKeyRepository.getCertificateBytes(keyId)
            if (key.keyType.startsWith("sk-")) {
                return ConnectionConfig.AuthMethod.FidoKey(
                    skKeyData = keyBytes,
                    certBytes = certBytes,
                    keyLabel = key.label,
                )
            }
            val effectivePassword = if (key.isEncrypted && password.isBlank()) {
                sshKeyRepository.getStoredPassphrase(keyId).orEmpty()
            } else {
                password
            }
            val passphrase = if (key.isEncrypted) effectivePassword.toCharArray() else CharArray(0)
            return ConnectionConfig.AuthMethod.PrivateKey(
                keyBytes = if (key.isEncrypted) keyBytes else SshKeyExporter.toPem(keyBytes, key.keyType),
                passphrase = passphrase,
                certificateBytes = certBytes,
            )
        }
        return null
    }

    private suspend fun resolveAnyUsableKeys(): ConnectionConfig.AuthMethod? {
        val entries = sshKeyRepository.getAllDecrypted()
            .filter { !it.keyType.startsWith("sk-") && it.enabledForAuth }
            .mapNotNull { key ->
                if (key.isEncrypted) {
                    val stored = sshKeyRepository.getStoredPassphrase(key.id)
                    if (stored.isNullOrEmpty()) return@mapNotNull null
                    ConnectionConfig.AuthMethod.PrivateKeys.KeyEntry(
                        label = key.label,
                        keyBytes = key.privateKeyBytes,
                        certificateBytes = key.certificateBytes,
                        passphrase = stored.toByteArray(Charsets.UTF_8),
                    )
                } else {
                    ConnectionConfig.AuthMethod.PrivateKeys.KeyEntry(
                        label = key.label,
                        keyBytes = SshKeyExporter.toPem(key.privateKeyBytes, key.keyType),
                        certificateBytes = key.certificateBytes,
                    )
                }
            }
        if (entries.isEmpty()) return null
        return ConnectionConfig.AuthMethod.PrivateKeys(keys = entries)
    }
}
