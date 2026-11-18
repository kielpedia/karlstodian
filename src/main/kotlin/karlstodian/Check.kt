package karlstodian

import io.ktor.client.plugins.*
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException

/** Turn common failures into something actionable. */
fun friendly(e: Throwable): String = when {
    e is ResponseException -> when (val code = e.response.status.value) {
        401, 403 -> "HTTP $code: wrong key/token, or access denied"
        404 -> "HTTP 404: reached a server, but not this app's API (check the port / URL)"
        else -> "HTTP $code"
    }
    e is SSLException || e.cause is SSLException ->
        "SSL error: ${e.message}. Try http://, or turn off certificate checks"
    e is HttpRequestTimeoutException || e is ConnectTimeoutException || e is java.net.SocketTimeoutException ->
        "Timed out (wrong IP, or a firewall in the way)"
    e is ConnectException || e is UnresolvedAddressException || e is UnknownHostException ->
        "Can't connect: ${e.message ?: "connection refused"} (check the IP and port)"
    e is SerializationException -> "Got a non-JSON reply. Is a login page or reverse proxy in front of it?"
    else -> "${e::class.simpleName}: ${e.message}"
}

private fun result(service: String, ok: Boolean, configured: Boolean = true, info: JsonObject? = null, error: String? = null) =
    buildJsonObject {
        put("service", service); put("ok", ok); put("configured", configured)
        if (info != null) put("info", info)
        if (error != null) put("error", error)
    }

suspend fun checkService(name: String, cfg: Settings = Config.current): JsonObject {
    val verify = cfg.verify_ssl
    val timeout = 10.0
    return try {
        if (cfg.get(SERVICES.getValue(name).first()).jsonPrimitive.content.isEmpty())
            return result(name, false, configured = false, error = "not configured")
        val info: JsonObject = when (name) {
            "plex" -> PlexClient(cfg.plex_url, cfg.plex_token, verify, timeout).use { c ->
                val ident = c.identity()
                val secs = c.sections()
                buildJsonObject {
                    put("version", ident["version"] ?: JsonNull)
                    putJsonArray("libraries") {
                        secs.filter { it.str("type") in setOf("movie", "show") }.forEach { add("${it.str("title")} (${it.str("type")})") }
                    }
                }
            }
            "tautulli" -> TautulliClient(cfg.tautulli_url, cfg.tautulli_api_key, verify, timeout).use { c ->
                buildJsonObject { put("plex_server", c.serverInfo()?.get("pms_name") ?: JsonNull) }
            }
            "radarr", "sonarr" -> {
                val c = if (name == "radarr") RadarrClient(cfg.radarr_url, cfg.radarr_api_key, verify, timeout)
                else SonarrClient(cfg.sonarr_url, cfg.sonarr_api_key, verify, timeout)
                c.use {
                    val st = it.systemStatus()
                    val app = st.str("appName") ?: name
                    if (app.lowercase() != name) return result(name, false, error = "That URL is $app, not $name")
                    buildJsonObject { put("version", st["version"] ?: JsonNull) }
                }
            }
            else -> QbitClient(cfg.qbit_url, cfg.qbit_username, cfg.qbit_password, verify, timeout).use { c ->
                buildJsonObject { put("version", c.version()) }
            }
        }
        result(name, true, info = info)
    } catch (e: Exception) {
        result(name, false, error = friendly(e))
    }
}

suspend fun checkAll(cfg: Settings = Config.current): List<JsonObject> = coroutineScope {
    SERVICES.keys.map { async { checkService(it, cfg) } }.awaitAll()
}

/** Connectivity check for each service: ./gradlew run --args=check */
fun runCheckCli() = runBlocking { checkAll().forEach { println(it) } }
