package sh.haven.feature.connections.tinder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.db.entities.ConnectionProfile
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject

data class TinShellCard(
    val host: String,
    val name: String,
    val alive: Boolean,
    val snapshotPlain: String?,
    val preview: String?,
    val running: Boolean,
    val waitingAsk: String?,
    val viaBase: String?,
    val isProtected: Boolean,
)

sealed interface TinDeleteResult {
    data object Ok : TinDeleteResult
    data class NeedsForce(val msg: String) : TinDeleteResult      // HTTP 409 {"error":"alive"}
    data class NeedsConfirm(val msg: String) : TinDeleteResult    // HTTP 403 {"error":"protected"}
    data class Error(val msg: String) : TinDeleteResult           // 404/400/mạng/parse
}

class TinPreviewClient @Inject constructor() {

    /** GET <baseUrl>/shells → list card. Ném exception khi lỗi mạng/HTTP/parse — caller xử. */
    suspend fun fetchShells(baseUrl: String): List<TinShellCard> = withContext(Dispatchers.IO) {
        val url = URL(baseUrl.trimEnd('/') + "/shells")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val rc = conn.responseCode
            if (rc !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw Exception("HTTP $rc: $err")
            }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(resp)
            val list = mutableListOf<TinShellCard>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name", "").ifEmpty { continue }
                val host = item.optString("host", "")
                val alive = item.optBoolean("session", false) || item.optString("status") == "alive"
                val snapshotPlain = item.optString("snapshot_plain", "").takeIf { it.isNotEmpty() && it != "null" }
                val preview = item.optString("preview", "").takeIf { it.isNotEmpty() && it != "null" }
                val running = item.optBoolean("running", false)
                
                val waitingObj = item.optJSONObject("waiting")
                val waitingAsk = waitingObj?.optString("ask")?.takeIf { it.isNotEmpty() }
                
                val viaBase = item.optString("via_base", "").takeIf { it.isNotEmpty() && it != "null" }
                val isProtected = item.optBoolean("protected", false)

                list.add(
                    TinShellCard(
                        host = host,
                        name = name,
                        alive = alive,
                        snapshotPlain = snapshotPlain,
                        preview = preview,
                        running = running,
                        waitingAsk = waitingAsk,
                        viaBase = viaBase,
                        isProtected = isProtected
                    )
                )
            }
            list
        } finally {
            conn.disconnect()
        }
    }

    /** DELETE <deleteBase>/shells/<name>[?force=1][&confirm=<name>] — KHÔNG ném cho 403/409,
     *  map thành TinDeleteResult để VM chạy state-machine dialog. */
    suspend fun deleteShell(
        deleteBase: String,
        name: String,
        force: Boolean,
        confirm: Boolean
    ): TinDeleteResult = withContext(Dispatchers.IO) {
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val params = mutableListOf<String>()
        if (force) {
            params.add("force=1")
        }
        if (confirm) {
            params.add("confirm=$encodedName")
        }
        val query = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
        val url = URL("${deleteBase.trimEnd('/')}/shells/$encodedName$query")
        
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "DELETE"
            setRequestProperty("Accept", "application/json")
        }
        
        try {
            val rc = conn.responseCode
            val stream = if (rc in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            
            if (rc in 200..299) {
                return@withContext TinDeleteResult.Ok
            }
            
            // Try to parse error response
            val errorJson = try { JSONObject(body) } catch (e: Exception) { null }
            val errorType = errorJson?.optString("error")
            val errorMsg = errorJson?.optString("msg") ?: errorJson?.optString("error") ?: "HTTP $rc: $body"
            
            when (rc) {
                409 -> {
                    if (errorType == "alive") {
                        return@withContext TinDeleteResult.NeedsForce(errorMsg)
                    }
                }
                403 -> {
                    if (errorType == "protected") {
                        return@withContext TinDeleteResult.NeedsConfirm(errorMsg)
                    }
                }
            }
            TinDeleteResult.Error(errorMsg)
        } catch (e: Throwable) {
            TinDeleteResult.Error("Network error: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Map ConnectionProfile to (host, name) Tin session key if it matches watchdog format. */
        fun tinSessionKeyOf(profile: ConnectionProfile): Pair<String, String>? {
            val prefix = "unset TMUX; exec tmux new -A -s "
            val cmd = profile.remoteCommand ?: return null
            if (!cmd.startsWith(prefix)) return null
            val sessionName = cmd.removePrefix(prefix).trim()
            if (sessionName.isEmpty()) return null
            val hostShort = profile.host.substringBefore('.')
            if (hostShort.isEmpty()) return null
            return Pair(hostShort, sessionName)
        }

        /** Resolve appropriate deleteBase base URL for a card. */
        fun deleteBaseFor(card: TinShellCard, prefBaseUrl: String): String {
            val via = card.viaBase
            return if (!via.isNullOrEmpty() && via != "null") {
                "${via.trimEnd('/')}/api"
            } else {
                prefBaseUrl
            }
        }
    }
}
