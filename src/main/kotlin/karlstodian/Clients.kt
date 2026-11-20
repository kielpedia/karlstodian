package karlstodian

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Tests swap in a MockEngine here. */
object Http {
    var engineOverride: HttpClientEngine? = null

    fun client(verify: Boolean, timeoutSeconds: Double): HttpClient {
        val config: HttpClientConfig<*>.() -> Unit = {
            expectSuccess = true
            followRedirects = true
            install(HttpTimeout) { requestTimeoutMillis = (timeoutSeconds * 1000).toLong() }
        }
        engineOverride?.let { return HttpClient(it) { config() } }
        return HttpClient(CIO) {
            config()
            engine {
                if (!verify) https { trustManager = TrustAll }
            }
        }
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}

/** Shared plumbing: base URL, default headers/params, JSON GETs. Close with use {}. */
abstract class ServiceClient(
    url: String,
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val params: Map<String, String> = emptyMap(),
    verify: Boolean? = null,
    timeout: Double? = null,
) : AutoCloseable {
    abstract val name: String
    val base = url.trimEnd('/')
    val http = Http.client(verify ?: Config.current.verify_ssl, timeout ?: Config.current.http_timeout)

    fun HttpRequestBuilder.defaults(path: String, query: Map<String, Any?>) {
        url(base + path)
        header(HttpHeaders.Accept, "application/json")
        defaultHeaders.forEach { (k, v) -> header(k, v) }
        (params + query).forEach { (k, v) -> if (v != null) parameter(k, v) }
    }

    open suspend fun get(path: String, query: Map<String, Any?> = emptyMap(), extraHeaders: Map<String, String> = emptyMap()): JsonElement {
        val r = http.get { defaults(path, query); extraHeaders.forEach { (k, v) -> header(k, v) } }
        return json.parseToJsonElement(r.bodyAsText())
    }

    /** POST/PUT/DELETE with an optional JSON body. Returns the response text (often empty). */
    open suspend fun send(
        method: HttpMethod, path: String, query: Map<String, Any?> = emptyMap(),
        body: JsonElement? = null, extraHeaders: Map<String, String> = emptyMap(),
    ): String = http.request {
        this.method = method
        defaults(path, query)
        extraHeaders.forEach { (k, v) -> header(k, v) }
        if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
    }.bodyAsText()

    override fun close() = http.close()
}

class PlexClient(url: String, token: String, verify: Boolean? = null, timeout: Double? = null) :
    ServiceClient(url, defaultHeaders = mapOf("X-Plex-Token" to token), verify = verify, timeout = timeout) {
    override val name = "plex"

    suspend fun identity(): JsonObject = get("/identity").obj.child("MediaContainer")!!
    suspend fun sections(): List<JsonObject> = get("/library/sections").obj.child("MediaContainer")?.get("Directory").arr.map { it.jsonObject }

    suspend fun machineId(): String = identity().str("machineIdentifier")
        ?: throw IllegalStateException("Plex didn't report its machineIdentifier")

    /** Collections in a library section: title -> ratingKey. */
    suspend fun collections(sectionKey: String): Map<String, String> =
        get("/library/sections/$sectionKey/collections").obj.child("MediaContainer")?.get("Metadata").arr
            .mapNotNull { m -> m.obj.str("title")?.let { t -> m.obj.str("ratingKey")?.let { t to it } } }.toMap()

    /** Rating keys of the items in a collection. */
    suspend fun collectionItems(collectionKey: String): Set<String> =
        get("/library/collections/$collectionKey/children").obj.child("MediaContainer")?.get("Metadata").arr
            .mapNotNull { it.obj.str("ratingKey") }.toSet()

    private fun itemsUri(machineId: String, ratingKeys: Collection<String>) =
        "server://$machineId/com.plexapp.plugins.library/library/metadata/${ratingKeys.joinToString(",")}"

    /** Creates a regular (non-smart) collection holding [ratingKeys]; type 1 = movies, 2 = shows. */
    suspend fun createCollection(sectionKey: String, title: String, type: Int, machineId: String, ratingKeys: Collection<String>) {
        send(HttpMethod.Post, "/library/collections", mapOf(
            "type" to type, "title" to title, "smart" to 0, "sectionId" to sectionKey, "uri" to itemsUri(machineId, ratingKeys),
        ))
    }

    /** Renames a collection (type 18 = collection) and locks the title so Plex doesn't change it back. */
    suspend fun renameCollection(sectionKey: String, collectionKey: String, title: String) {
        send(HttpMethod.Put, "/library/sections/$sectionKey/all", mapOf(
            "type" to 18, "id" to collectionKey, "title.value" to title, "title.locked" to 1,
        ))
    }

    suspend fun addToCollection(collectionKey: String, machineId: String, ratingKeys: Collection<String>) {
        send(HttpMethod.Put, "/library/collections/$collectionKey/items", mapOf("uri" to itemsUri(machineId, ratingKeys)))
    }

    suspend fun removeFromCollection(collectionKey: String, ratingKey: String) {
        send(HttpMethod.Delete, "/library/collections/$collectionKey/children/$ratingKey")
    }

    suspend fun deleteCollection(collectionKey: String) {
        send(HttpMethod.Delete, "/library/collections/$collectionKey")
    }

    /** True if the collection is already pinned (shown as a row on Recommended / Home). */
    suspend fun collectionPinned(sectionKey: String, collectionKey: String): Boolean =
        get("/hubs/sections/$sectionKey/manage", mapOf("metadataItemId" to collectionKey)).obj
            .child("MediaContainer")?.get("Hub").arr.isNotEmpty()

    /** Pins a collection as a row on the library's Recommended tab, your Home and your friends' Home. */
    suspend fun pinCollection(sectionKey: String, collectionKey: String) {
        send(HttpMethod.Post, "/hubs/sections/$sectionKey/manage", mapOf(
            "metadataItemId" to collectionKey,
            "promotedToRecommended" to 1, "promotedToOwnHome" to 1, "promotedToSharedHome" to 1,
        ))
    }

    /** Ask Plex to rescan a library section (after files were deleted). */
    suspend fun refreshSection(sectionKey: String) {
        get("/library/sections/$sectionKey/refresh")
    }

    /** All top-level items (movies or shows) in a library, paged. */
    suspend fun sectionItems(sectionKey: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var start = 0
        while (true) {
            val mc = get(
                "/library/sections/$sectionKey/all", mapOf("includeGuids" to 1),
                mapOf("X-Plex-Container-Start" to "$start", "X-Plex-Container-Size" to "$PAGE"),
            ).obj.child("MediaContainer")
            val batch = mc?.get("Metadata").arr.map { it.jsonObject }
            out += batch
            val total = mc.int("totalSize") ?: out.size
            start += batch.size
            if (batch.isEmpty() || start >= total) return out
        }
    }

    companion object {
        const val PAGE = 500

        /** {"tmdb": "123", "tvdb": "456", "imdb": "tt..."} from the Guid list. */
        fun externalIds(meta: JsonObject): Map<String, String> = buildMap {
            for (g in meta["Guid"].arr) {
                val id = g.obj.str("id") ?: ""
                val scheme = id.substringBefore("://", "")
                val value = id.substringAfter("://", "")
                if (scheme.isNotEmpty() && value.isNotEmpty()) put(scheme, value)
            }
        }
    }
}

class TautulliClient(url: String, apiKey: String, verify: Boolean? = null, timeout: Double? = null) :
    ServiceClient(url, params = mapOf("apikey" to apiKey), verify = verify, timeout = timeout) {
    override val name = "tautulli"

    suspend fun cmd(cmd: String, params: Map<String, Any?> = emptyMap()): JsonElement? {
        val resp = get("/api/v2", mapOf("cmd" to cmd) + params).obj.child("response")
        if (resp.str("result") != "success") throw IllegalStateException("Tautulli $cmd failed: ${resp.str("message")}")
        return resp?.get("data")
    }

    suspend fun serverInfo(): JsonObject? = cmd("get_server_info").obj

    /** Every history row across all users (movies and episodes). */
    suspend fun history(): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var start = 0
        while (true) {
            val rows = cmd("get_history", mapOf("start" to start, "length" to PAGE, "order_column" to "date", "order_dir" to "desc"))
                .obj?.get("data").arr.map { it.jsonObject }
            out += rows
            start += rows.size
            if (rows.size < PAGE) return out
        }
    }

    companion object { const val PAGE = 1000 }
}

abstract class ArrClient(url: String, apiKey: String, verify: Boolean?, timeout: Double?) :
    ServiceClient(url, defaultHeaders = mapOf("X-Api-Key" to apiKey), verify = verify, timeout = timeout) {

    suspend fun systemStatus(): JsonObject = get("/api/v3/system/status").jsonObject

    /** The movie / series, or null when it no longer exists. */
    abstract suspend fun find(id: Long): JsonObject?
    /** Ids of the movie's / series' current files. */
    abstract suspend fun fileIds(id: Long): List<Long>
    /** Stop searching for it, keeping the entry. */
    abstract suspend fun unmonitor(id: Long)
    /** Delete files from disk (through Radarr/Sonarr, so their database stays in step). */
    abstract suspend fun deleteFiles(fileIds: List<Long>)

    protected suspend fun findAt(path: String): JsonObject? = try {
        get(path).jsonObject
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound) null else throw e
    }

    protected fun idsBody(field: String, ids: List<Long>, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        putJsonArray(field) { ids.forEach { add(it) } }
        extra()
    }
    suspend fun tags(): Map<Long, String> = get("/api/v3/tag").arr.associate { it.obj.long("id")!! to (it.obj.str("label") ?: "") }

    /** All "imported from download client" history records. Each carries the torrent hash
     *  (downloadId) and the path the file was imported to. */
    suspend fun importHistory(): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var page = 1
        while (true) {
            val data = get("/api/v3/history", mapOf(
                "page" to page, "pageSize" to PAGE, "eventType" to EVENT_DOWNLOAD_FOLDER_IMPORTED,
                "sortKey" to "date", "sortDirection" to "descending",
            )).obj
            val records = data?.get("records").arr.map { it.jsonObject }
            out += records
            if (out.size >= (data.long("totalRecords") ?: 0) || records.isEmpty()) return out
            page++
        }
    }

    companion object {
        const val PAGE = 1000
        const val EVENT_DOWNLOAD_FOLDER_IMPORTED = 3 // history eventType for completed imports (v3 API)
    }
}

class RadarrClient(url: String, apiKey: String, verify: Boolean? = null, timeout: Double? = null) :
    ArrClient(url, apiKey, verify, timeout) {
    override val name = "radarr"
    suspend fun movies(): List<JsonObject> = get("/api/v3/movie").arr.map { it.jsonObject }

    override suspend fun find(id: Long) = findAt("/api/v3/movie/$id")
    override suspend fun fileIds(id: Long): List<Long> =
        get("/api/v3/moviefile", mapOf("movieId" to id)).arr.mapNotNull { it.obj.long("id") }
    override suspend fun unmonitor(id: Long) {
        send(HttpMethod.Put, "/api/v3/movie/editor", body = idsBody("movieIds", listOf(id)) { put("monitored", false) })
    }
    override suspend fun deleteFiles(fileIds: List<Long>) {
        if (fileIds.isNotEmpty()) send(HttpMethod.Delete, "/api/v3/moviefile/bulk", body = idsBody("movieFileIds", fileIds))
    }
}

class SonarrClient(url: String, apiKey: String, verify: Boolean? = null, timeout: Double? = null) :
    ArrClient(url, apiKey, verify, timeout) {
    override val name = "sonarr"
    suspend fun series(): List<JsonObject> = get("/api/v3/series").arr.map { it.jsonObject }
    suspend fun episodeFiles(seriesId: Long): List<JsonObject> =
        get("/api/v3/episodefile", mapOf("seriesId" to seriesId)).arr.map { it.jsonObject }

    override suspend fun find(id: Long) = findAt("/api/v3/series/$id")
    override suspend fun fileIds(id: Long): List<Long> = episodeFiles(id).mapNotNull { it.long("id") }
    override suspend fun unmonitor(id: Long) {
        send(HttpMethod.Put, "/api/v3/series/editor", body = idsBody("seriesIds", listOf(id)) { put("monitored", false) })
    }
    override suspend fun deleteFiles(fileIds: List<Long>) {
        if (fileIds.isNotEmpty()) send(HttpMethod.Delete, "/api/v3/episodefile/bulk", body = idsBody("episodeFileIds", fileIds))
    }

    /** Episode files for every series, 8 requests at a time. */
    suspend fun episodeFilesFor(series: List<JsonObject>): Map<Long, List<JsonObject>> = coroutineScope {
        val sem = Semaphore(8)
        series.map { s ->
            val id = s.long("id")!!
            async { sem.withPermit { id to episodeFiles(id) } }
        }.awaitAll().toMap()
    }
}

class QbitClient(url: String, private val username: String, private val password: String,
                 verify: Boolean? = null, timeout: Double? = null) :
    // qBittorrent rejects API calls whose Referer/Origin don't match its host
    ServiceClient(url, defaultHeaders = mapOf("Referer" to url.trimEnd('/')), verify = verify, timeout = timeout) {
    override val name = "qbittorrent"
    private var cookie: String? = null
    private var loggedIn = false

    suspend fun login() {
        val r = http.submitForm(
            url = "$base/api/v2/auth/login",
            formParameters = parameters { append("username", username); append("password", password) },
        ) { header("Referer", base) }
        val text = r.bodyAsText().trim()
        if (text != "Ok." && text != "") throw IllegalStateException("qBittorrent login failed (check username/password)")
        cookie = r.setCookie().firstOrNull { it.name == "SID" }?.let { "${it.name}=${it.value}" }
        loggedIn = true
    }

    private fun cookieHeader() = cookie?.let { mapOf(HttpHeaders.Cookie to it) } ?: emptyMap()

    override suspend fun get(path: String, query: Map<String, Any?>, extraHeaders: Map<String, String>): JsonElement {
        if (!loggedIn) login()
        return super.get(path, query, extraHeaders + cookieHeader())
    }

    suspend fun version(): String {
        if (!loggedIn) login()
        return http.get { defaults("/api/v2/app/version", emptyMap()); cookieHeader().forEach { (k, v) -> header(k, v) } }.bodyAsText()
    }

    suspend fun torrents(): List<JsonObject> = get("/api/v2/torrents/info").arr.map { it.jsonObject }

    /** Live info for specific torrents (missing hashes are simply absent). */
    suspend fun torrents(hashes: Collection<String>): List<JsonObject> =
        if (hashes.isEmpty()) emptyList()
        else get("/api/v2/torrents/info", mapOf("hashes" to hashes.joinToString("|"))).arr.map { it.jsonObject }

    /** Remove torrents together with their downloaded data. */
    suspend fun deleteWithData(hashes: Collection<String>) {
        if (hashes.isEmpty()) return
        if (!loggedIn) login()
        http.submitForm(
            url = "$base/api/v2/torrents/delete",
            formParameters = parameters { append("hashes", hashes.joinToString("|")); append("deleteFiles", "true") },
        ) { header("Referer", base); cookieHeader().forEach { (k, v) -> header(k, v) } }
    }
}
