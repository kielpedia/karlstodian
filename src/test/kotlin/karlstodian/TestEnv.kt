package karlstodian

import io.ktor.client.engine.mock.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.*
import java.nio.file.Files
import java.nio.file.Path

/** Fresh settings + database in a temp dir, with real hardlinked files and mocked services. */
class TestEnv {
    val dir: Path = Files.createTempDirectory("karlstodian-test")
    val media: Path = dir.resolve("media")
    var radarrOk = true
    /** Extra handlers checked before the standard ones: (host, path) -> response */
    val overrides = mutableMapOf<Pair<String, String>, Pair<HttpStatusCode, String>>()

    /** Every request that changed something (not GET), as "METHOD host/path?query body". */
    val writes = mutableListOf<String>()

    init {
        Files.createDirectories(media.resolve("torrents"))
        Files.createDirectories(media.resolve("library/Movie A"))
        Files.createDirectories(media.resolve("library/Show B"))
        // torrent copy + hardlinked library copy
        val src = media.resolve("torrents/movie.a.mkv")
        Files.write(src, ByteArray(100) { 'x'.code.toByte() })
        Files.createLink(media.resolve("library/Movie A/Movie A.mkv"), src)
        val ep = media.resolve("torrents/show.b.s01e01.mkv")
        Files.write(ep, ByteArray(50) { 'y'.code.toByte() })
        Files.createLink(media.resolve("library/Show B/S01E01.mkv"), ep)

        Store.reset()
        Config.reload(mapOf(
            "PRUNER_DB_PATH" to dir.resolve("t.db").toString(),
            "PRUNER_PLEX_URL" to "http://plex", "PRUNER_PLEX_TOKEN" to "t",
            "PRUNER_TAUTULLI_URL" to "http://tautulli", "PRUNER_TAUTULLI_API_KEY" to "k",
            "PRUNER_RADARR_URL" to "http://radarr", "PRUNER_RADARR_API_KEY" to "k",
            "PRUNER_SONARR_URL" to "http://sonarr", "PRUNER_SONARR_API_KEY" to "k",
            "PRUNER_QBIT_URL" to "http://qbit", "PRUNER_QBIT_USERNAME" to "u", "PRUNER_QBIT_PASSWORD" to "p",
            "PRUNER_PATH_MAPS" to """[["/data", "${media.toString().replace('\\', '/')}"]]""",
            "PRUNER_SCAN_CRON" to "",
        ))
        Http.engineOverride = MockEngine { req ->
            val key = req.url.host to req.url.encodedPath
            if (req.method != HttpMethod.Get && key.second != "/api/v2/auth/login") {
                val body = when (val b = req.body) {
                    is TextContent -> b.text
                    is FormDataContent -> b.formData.entries().joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" }
                    else -> ""
                }
                val q = req.url.encodedQuery.let { if (it.isEmpty()) "" else "?" + it.decodeURLQueryComponent(plusIsSpace = true) }
                synchronized(writes) { writes += "${req.method.value} ${key.first}${key.second}$q $body".trim() }
            }
            val (status, body) = overrides[key] ?: respond(key.first, key.second, req.method)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private fun respond(host: String, path: String, method: HttpMethod): Pair<HttpStatusCode, String> {
        val ok = HttpStatusCode.OK
        return when ("$host$path") {
            "radarr/api/v3/movie" -> if (!radarrOk) HttpStatusCode.InternalServerError to "{}" else ok to """[
                {"id": 10, "title": "Movie A", "year": 2001, "tmdbId": 111, "imdbId": "tt1", "tags": [1], "movieFile":
                  {"id": 1, "path": "/data/library/Movie A/Movie A.mkv", "size": 100,
                   "quality": {"quality": {"name": "Bluray-720p", "resolution": 720}}, "mediaInfo": {"videoCodec": "x264"}}},
                {"id": 11, "title": "Missing", "tmdbId": 222, "tags": []}]"""
            "radarr/api/v3/tag" -> ok to """[{"id": 1, "label": "prune-quality-ok"}]"""
            "radarr/api/v3/history" -> ok to """{"totalRecords": 1, "records": [
                {"movieId": 10, "downloadId": "ABC123", "data": {"importedPath": "/data/library/Movie A/Movie A.mkv"}}]}"""
            "sonarr/api/v3/series" -> ok to """[{"id": 20, "title": "Show B", "tvdbId": 333, "tags": []}]"""
            "sonarr/api/v3/tag" -> ok to "[]"
            "sonarr/api/v3/history" -> ok to """{"totalRecords": 1, "records": [
                {"seriesId": 20, "downloadId": "DEF456", "data": {"importedPath": "/data/library/Show B/S01E01.mkv"}}]}"""
            "sonarr/api/v3/episodefile" -> ok to """[{"id": 5, "path": "/data/library/Show B/S01E01.mkv", "size": 50,
                "quality": {"quality": {"name": "WEBDL-1080p", "resolution": 1080}}}]"""
            "plex/library/sections" -> ok to """{"MediaContainer": {"Directory": [
                {"key": "1", "type": "movie", "title": "Movies"}, {"key": "2", "type": "show", "title": "TV"}]}}"""
            "plex/library/sections/1/all" -> ok to """{"MediaContainer": {"totalSize": 1, "Metadata": [
                {"ratingKey": "900", "addedAt": 1600000000, "viewCount": 2, "lastViewedAt": 1700000000, "Guid": [{"id": "tmdb://111"}]}]}}"""
            "plex/library/sections/2/all" -> ok to """{"MediaContainer": {"totalSize": 1, "Metadata": [
                {"ratingKey": "800", "addedAt": 1650000000, "leafCount": 10, "viewedLeafCount": 3, "Guid": [{"id": "tvdb://333"}]}]}}"""
            "tautulli/api/v2" -> ok to """{"response": {"result": "success", "data": {"data": [
                {"media_type": "movie", "rating_key": 900, "user": "kiel", "friendly_name": "Kiel", "watched_status": 1, "stopped": 1700000000},
                {"media_type": "movie", "rating_key": 900, "user": "guest", "friendly_name": "Guest", "watched_status": 0, "stopped": 1710000000},
                {"media_type": "episode", "rating_key": 801, "grandparent_rating_key": 800, "parent_media_index": 1, "media_index": 1, "friendly_name": "Kiel", "watched_status": 1, "stopped": 1690000000}
                ]}}}"""
            "qbit/api/v2/auth/login" -> ok to "Ok."
            "qbit/api/v2/torrents/info" -> ok to """[{"hash": "abc123", "name": "movie.a", "size": 100, "ratio": 1.5,
                "seeding_time": 86400, "tracker": "https://t/announce", "added_on": 1600000000}]"""
            else -> if (method != HttpMethod.Get) ok to "" else HttpStatusCode.NotFound to "{}" // writes succeed by default
        }
    }

    /** POSIX file attributes (inodes, link counts, modes) exist on the Linux server, not on Windows. */
    val posix: Boolean = !System.getProperty("os.name").lowercase().contains("win")

    fun close() {
        Http.engineOverride = null
        Store.reset()
    }
}
