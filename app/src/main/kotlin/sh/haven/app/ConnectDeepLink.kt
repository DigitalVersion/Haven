package sh.haven.app

import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.db.entities.ConnectionProfile

/**
 * Pure parsing + saved-profile matching for the `haven://connect` deep link
 * (#305). Kept free of `android.net.Uri` (the caller adapts it via the
 * [parse] query getter) so the parameter handling and host matching are
 * unit-testable on the JVM.
 *
 * Behaviour: a link identifies a host (plus optional user/port/transport).
 * If exactly one saved profile matches, we connect it; otherwise, when the
 * link also carries a [Params.keyId] referencing a key already present on
 * this device (so no credential material travels through the link itself),
 * we offer to create a new profile from the link's fields and connect it —
 * gated behind a one-tap confirm, same as the matched-profile path, since a
 * `BROWSABLE` link can be fired by any app or web page. A link that carries
 * neither a match nor a `keyId` falls back to the pre-filled New-Connection
 * editor, unchanged from before.
 */
object ConnectDeepLink {

    data class Params(
        val host: String,
        val username: String?,
        val port: Int?,
        /** `ssh` / `mosh` / `et`, or null when the link didn't specify one. */
        val transport: String?,
        val session: String?,
        /** Optional remote command (Tin attach / clean tmux). `command` or `startupCommand` query. */
        val command: String? = null,
        val id: String? = null,
        /** References an existing on-device key (see [ConnectDeepLink] doc) — never key material itself. */
        val keyId: String? = null,
        /** Display label for an auto-created profile; falls back to [host] when absent. */
        val label: String? = null,
    )

    /**
     * Build [Params] from a query-parameter getter (e.g. `uri::getQueryParameter`).
     * Returns null when no `host` (and no `id`) is present — a connect link without
     * a host or id is a no-op rather than an error.
     */
    fun parse(query: (String) -> String?): Params? {
        val host = query("host")?.trim()?.takeIf { it.isNotEmpty() }
        val id = query("id")?.trim()?.takeIf { it.isNotEmpty() }
        if (host == null && id == null) return null
        return Params(
            host = host ?: "",
            username = query("user")?.trim()?.takeIf { it.isNotEmpty() },
            port = query("port")?.trim()?.toIntOrNull(),
            transport = query("transport")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            session = query("session")?.trim()?.takeIf { it.isNotEmpty() },
            command = (query("command") ?: query("startupCommand"))?.trim()?.takeIf { it.isNotEmpty() },
            id = id,
            keyId = query("keyId")?.trim()?.takeIf { it.isNotEmpty() },
            label = query("label")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Whether [profile] is the SSH-family transport named by [transport]. */
    fun matchesTransport(profile: ConnectionProfile, transport: String): Boolean =
        when (transport) {
            "mosh" -> profile.connectionType == "SSH" && profile.useMosh
            "et", "eternal", "eternalterminal" -> profile.connectionType == "SSH" && profile.useEternalTerminal
            // Plain "ssh" matches any SSH-family profile for the host (incl.
            // mosh/ET-enabled ones) rather than excluding them on a technicality.
            "ssh" -> profile.connectionType == "SSH"
            else -> true
        }

    /** Saved profiles whose host (and any supplied user/port/transport) match [p]. */
    fun matches(profiles: List<ConnectionProfile>, p: Params): List<ConnectionProfile> {
        if (p.id != null) {
            val byId = profiles.filter { it.id == p.id }
            // An id that still exists is the exact answer and wins outright.
            // One that doesn't is a stale link — a profile deleted and recreated
            // keeps its host but gets a new id — so fall through to host matching
            // when the link carried a host too, rather than returning nothing and
            // opening a New-Connection editor with a blank host.
            if (byId.isNotEmpty() || p.host.isEmpty()) return byId
        }
        val candidates = profiles.filter { prof ->
            prof.host.equals(p.host, ignoreCase = true) &&
                (p.username == null || prof.username.equals(p.username, ignoreCase = true)) &&
                (p.port == null || prof.port == p.port) &&
                (p.transport == null || matchesTransport(prof, p.transport))
        }
        if (candidates.size <= 1) {
            return candidates
        }
        val narrowCandidates = candidates.filter { prof ->
            val cmd = prof.remoteCommand
            if (cmd == null) {
                false
            } else {
                val matchSession = p.session == null || cmd.contains(p.session, ignoreCase = true)
                val matchCommand = p.command == null || cmd.contains(p.command, ignoreCase = true)
                if (p.session == null && p.command == null) {
                    false
                } else {
                    matchSession && matchCommand
                }
            }
        }
        return if (narrowCandidates.size == 1) {
            narrowCandidates
        } else {
            candidates
        }
    }

    /**
     * Whether [p] carries enough to build a working profile without any
     * manual edit: a host, a username, and a [Params.keyId] that [keyExists]
     * confirms is already on this device. The key check is what keeps this
     * from being "a link creates a profile out of thin air" — the link only
     * ever *references* a key the user already trusted onto the device by
     * some other, credential-carrying path (import, generate, paste).
     */
    fun canAutoCreate(p: Params, keyExists: (String) -> Boolean): Boolean =
        p.host.isNotEmpty() && p.username != null && p.keyId != null && keyExists(p.keyId)

    /**
     * The command to emit for [p]: connect a single matched profile; else,
     * when [canAutoCreate] holds, build-and-connect a new one (still gated by
     * a one-tap confirm upstream, same as the matched path); else fall back
     * to the pre-filled New-Connection editor.
     */
    fun resolve(
        profiles: List<ConnectionProfile>,
        p: Params,
        keyExists: (String) -> Boolean = { false },
    ): AgentUiCommand {
        val match = matches(profiles, p).singleOrNull()
        if (match != null) {
            return AgentUiCommand.ConnectFromDeepLink(match.id, p.session, p.command)
        }
        if (canAutoCreate(p, keyExists)) {
            return AgentUiCommand.CreateAndConnectFromDeepLink(
                host = p.host,
                username = requireNotNull(p.username),
                port = p.port ?: 22,
                transport = p.transport ?: "ssh",
                session = p.session,
                command = p.command,
                keyId = requireNotNull(p.keyId),
                label = p.label ?: p.host,
            )
        }
        return AgentUiCommand.PrefillNewConnection(p.host, p.username, p.port, p.transport ?: "ssh", p.session)
    }
}
