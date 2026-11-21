package karlstodian

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/*
 * Posters and backdrops for the Library page. The browser asks Karlstodian, which fetches the
 * image from Plex (resized by Plex's transcoder) or, for titles not in Plex, from Radarr/Sonarr,
 * so the Plex token and API keys never reach the browser. Images are cached on disk next to the
 * database (cache/images); a missing image is remembered for a day so it isn't asked for again.
 */
object Images {
    private val log = LoggerFactory.getLogger("karlstodian.images")
    private val fetches = Semaphore(6) // don't flood Plex when a grid of posters loads
    private val MISS_TTL: Duration = Duration.ofDays(1)

    enum class Kind(val width: Int, val height: Int) {
        POSTER(300, 450), ART(960, 540);
        companion object { fun of(s: String) = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
    }

    data class Image(val bytes: ByteArray, val contentType: String)

    fun cacheDir(cfg: Settings = Config.current): Path = Config.settingsFile(cfg).parent.resolve("cache").resolve("images")

    /** The image, or null when there isn't one. */
    suspend fun get(item: Item, kind: Kind, cfg: Settings = Config.current): Image? {
        val plexPath = if (kind == Kind.POSTER) item.plex_thumb else item.plex_art
        // the source is part of the cache key, so a changed poster in Plex is fetched again
        val source = if (plexPath != null && cfg.plex_url.isNotEmpty()) "plex:$plexPath" else "arr:${item.key}"
        val file = cacheDir(cfg).resolve("${sha1("$source|${kind.name}|${kind.width}")}.img")
        val miss = file.resolveSibling(file.fileName.toString() + ".miss")

        if (Files.exists(file)) return Files.readAllBytes(file).let { Image(it, sniff(it)) }
        if (Files.exists(miss) && Files.getLastModifiedTime(miss).toInstant().isAfter(Instant.now().minus(MISS_TTL))) return null

        val bytes = fetches.withPermit {
            if (Files.exists(file)) return@withPermit Files.readAllBytes(file) // fetched while this one waited
            try {
                if (source.startsWith("plex:")) fromPlex(plexPath!!, kind, cfg) else fromArr(item, kind, cfg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("no {} for {}: {}", kind, item.key, e.message)
                null
            }
        }
        Files.createDirectories(file.parent)
        if (bytes == null || bytes.isEmpty()) {
            Files.write(miss, ByteArray(0))
            return null
        }
        // write then move, so a half-written file is never served
        val tmp = Files.createTempFile(file.parent, "img", ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Files.deleteIfExists(miss)
        return Image(bytes, sniff(bytes))
    }

    private suspend fun fromPlex(path: String, kind: Kind, cfg: Settings): ByteArray =
        PlexClient(cfg.plex_url, cfg.plex_token).use { p ->
            p.bytes("/photo/:/transcode", mapOf(
                "url" to path, "width" to kind.width, "height" to kind.height,
                "minSize" to 1, "upscale" to 1, "format" to "jpeg", "quality" to 80,
            )).first
        }

    /** Radarr/Sonarr keep resized covers: poster-500.jpg, fanart-360.jpg (fanart.jpg is full size). */
    private suspend fun fromArr(item: Item, kind: Kind, cfg: Settings): ByteArray? {
        val file = if (kind == Kind.POSTER) "poster-500.jpg" else "fanart.jpg"
        val path = "/api/v3/mediacover/${item.arr_id}/$file"
        return when (item.kind) {
            "movie" -> if (cfg.radarr_url.isEmpty()) null else RadarrClient(cfg.radarr_url, cfg.radarr_api_key).use { it.bytes(path).first }
            else -> if (cfg.sonarr_url.isEmpty()) null else SonarrClient(cfg.sonarr_url, cfg.sonarr_api_key).use { it.bytes(path).first }
        }
    }

    /** Content type from the first bytes (the cache doesn't keep headers). */
    fun sniff(b: ByteArray): String = when {
        b.size > 3 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b.size > 12 && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
