package sh.haven.core.ssh

import sh.haven.core.data.db.entities.ConnectionProfile

interface SshKeyResolver {
    suspend fun resolveAuthMethod(profile: ConnectionProfile, password: String): ConnectionConfig.AuthMethod
    suspend fun agentIdentitiesFor(profile: ConnectionProfile): List<ConnectionConfig.AgentIdentity>
}
