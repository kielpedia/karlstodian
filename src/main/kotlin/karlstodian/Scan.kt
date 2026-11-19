package karlstodian

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.time.Instant

/*
 * The scan job: pull everything from the services, join it per movie / per series,
 * and cache it in SQLite. Read-only: it never changes anything on the services.
 */

private val log = LoggerFactory.getLogger("karlstodian.scan")
val scanLock = Mutex()

/** Unix epoch (number or numeric string) -> Instant; 0, blanks and junk -> null. */
fun epoch(v: JsonElement?): Instant? {
    val p = v as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    val n = p.longOrNull ?: p.doubleOrNull?.toLong() ?: return null
    return if (n > 0) Instant.ofEpochSecond(n) else null
}

private val RES_RE = Regex("x(\\d{3,4})")

fun resolution(file: JsonObject): Int? {
    file.child("quality").child("quality").int("resolution")?.takeIf { it != 0 }?.let { return it }
    val m = RES_RE.find(file.child("mediaInfo").str("resolution") ?: "")
    return m?.groupValues?.get(1)?.toInt()
}

data class FileStat(val local: String?, val dev: Long?, val ino: Long?, val nlink: Long?)

/** Where the file is on this machine, plus its filesystem identity for hardlink accounting. */
fun statFile(path: String, cfg: Settings): FileStat {
    val local = cfg.mapPath(path) ?: return FileStat(null, null, null, null)
    return try {
        val p = Paths.get(local) // can throw on Windows for names with ':' etc.
        if (!Files.exists(p)) return FileStat(local, null, null, null)
        val a = Files.readAttributes(p, "unix:dev,ino,nlink", LinkOption.NOFOLLOW_LINKS)
        FileStat(local, (a["dev"] as Number).toLong(), (a["ino"] as Number).toLong(), (a["nlink"] as Number).toLong())
    } catch (_: UnsupportedOperationException) {
        FileStat(local, null, null, null) // Windows: no inode info (the server is Linux)
    } catch (_: Exception) {
        FileStat(local, null, null, null)
    }
}

// ------------------------------------------------------------------ fetching

private data class RadarrData(val movies: List<JsonObject> = emptyList(), val tags: Map<Long, String> = emptyMap(), val history: List<JsonObject> = emptyList())
private data class SonarrData(val series: List<JsonObject> = emptyList(), val tags: Map<Long, String> = emptyMap(),
                              val history: List<JsonObject> = emptyList(), val files: Map<Long, List<JsonObject>> = emptyMap())
private data class PlexData(val movies: List<JsonObject> = emptyList(), val shows: List<JsonObject> = emptyList())

private suspend fun fetchRadarr(s: Settings): RadarrData {
    if (s.radarr_url.isEmpty()) return RadarrData()
    return RadarrClient(s.radarr_url, s.radarr_api_key).use { c ->
        coroutineScope {
            val m = async { c.movies() }; val t = async { c.tags() }; val h = async { c.importHistory() }
            RadarrData(m.await(), t.await(), h.await())
        }
    }
}

private suspend fun fetchSonarr(s: Settings): SonarrData {
    if (s.sonarr_url.isEmpty()) return SonarrData()
    return SonarrClient(s.sonarr_url, s.sonarr_api_key).use { c ->
        coroutineScope {
            val sr = async { c.series() }; val t = async { c.tags() }; val h = async { c.importHistory() }
            val series = sr.await()
            SonarrData(series, t.await(), h.await(), c.episodeFilesFor(series))
        }
    }
}

private suspend fun fetchPlex(s: Settings): PlexData {
    if (s.plex_url.isEmpty()) return PlexData()
    return PlexClient(s.plex_url, s.plex_token).use { c ->
        val movies = mutableListOf<JsonObject>()
        val shows = mutableListOf<JsonObject>()
        for (sec in c.sections()) {
            when (sec.str("type")) {
                "movie" -> movies += c.sectionItems(sec.str("key")!!).map { it.withSection(sec) }
                "show" -> shows += c.sectionItems(sec.str("key")!!).map { it.withSection(sec) }
            }
        }
        PlexData(movies, shows)
    }
}

/** Remember which library section an item came from (the Leaving Soon collection is per section). */
private fun JsonObject.withSection(sec: JsonObject) = JsonObject(this + (SECTION to JsonPrimitive(sec.str("key"))))
private const val SECTION = "_karlstodian_section"

private suspend fun fetchTautulli(s: Settings): List<JsonObject> =
    if (s.tautulli_url.isEmpty()) emptyList()
    else TautulliClient(s.tautulli_url, s.tautulli_api_key).use { it.history() }

private suspend fun fetchQbit(s: Settings): List<JsonObject> =
    if (s.qbit_url.isEmpty()) emptyList()
    else QbitClient(s.qbit_url, s.qbit_username, s.qbit_password).use { it.torrents() }

// ------------------------------------------------------------------ joining

/** importedPath -> hash, plus (owner id -> most recent hash) as a fallback. History is newest first. */
fun hashIndex(history: List<JsonObject>, idField: String): Pair<Map<String, String>, Map<Long, String>> {
    val byPath = HashMap<String, String>()
    val byOwner = HashMap<Long, String>()
    for (rec in history) {
        val h = rec.str("downloadId")?.lowercase().orEmpty()
        if (h.isEmpty()) continue
        rec.child("data").str("importedPath")?.takeIf { it.isNotEmpty() }?.let { byPath.putIfAbsent(it, h) }
        rec.long(idField)?.let { byOwner.putIfAbsent(it, h) }
    }
    return byPath to byOwner
}

/** Season/episode numbers survive Plex re-matching; rating keys don't. */
fun episodeId(row: JsonObject): String {
    val season = row.str("parent_media_index")
    val ep = row.str("media_index")
    return if (!season.isNullOrEmpty() && !ep.isNullOrEmpty()) "$season/$ep" else "rk/${row.str("rating_key")}"
}

class PlayStats {
    var plays = 0
    var last: Instant? = null
    val users = HashSet<String>()
    val eps = HashSet<String>()
}

/** Tautulli rows -> per Plex rating key totals and per (rating key, user) stats.
 *  Episodes roll up to their show (grandparent rating key). */
fun aggregatePlays(history: List<JsonObject>): Pair<Map<String, PlayStats>, Map<Pair<String, String>, PlayStats>> {
    val totals = LinkedHashMap<String, PlayStats>()
    val perUser = LinkedHashMap<Pair<String, String>, PlayStats>()
    for (row in history) {
        val mt = row.str("media_type")
        val key = when (mt) {
            "movie" -> row.str("rating_key")
            "episode" -> row.str("grandparent_rating_key")
            else -> continue
        }
        if (key.isNullOrEmpty() || key == "0") continue
        val user = row.str("friendly_name").orIfBlank { row.str("user") } ?: "unknown"
        val stopped = epoch(row["stopped"])
        val whenT = stopped ?: epoch(row["date"])
        val ws = row["watched_status"] as? JsonPrimitive
        val watched = ws != null && !ws.isString && ws.doubleOrNull == 1.0 // Tautulli's own "watched" threshold
        val t = totals.getOrPut(key) { PlayStats() }
        val u = perUser.getOrPut(key to user) { PlayStats() }
        t.users += user
        if (watched) {
            t.plays++; u.plays++
            if (mt == "episode") {
                val ep = episodeId(row)
                t.eps += ep; u.eps += ep
            }
        }
        if (whenT != null) {
            if (t.last.let { it == null || whenT > it }) t.last = whenT
            if (u.last.let { it == null || whenT > it }) u.last = whenT
        }
    }
    return totals to perUser
}

private fun fileRow(f: JsonObject, torrentHash: String?, cfg: Settings): MediaFile {
    val path = f.str("path")!!
    val st = statFile(path, cfg)
    return MediaFile(
        arr_file_id = f.long("id")!!,
        path = path,
        local_path = st.local,
        size = f.long("size") ?: 0,
        resolution = resolution(f),
        quality_name = f.child("quality").child("quality").str("name"),
        video_codec = f.child("mediaInfo").str("videoCodec"),
        torrent_hash = torrentHash,
        device = st.dev, inode = st.ino, nlink = st.nlink,
    )
}

private fun applyFiles(item: Item, files: List<MediaFile>, cfg: Settings) {
    item.size_bytes = files.sumOf { it.size }
    item.low_quality_bytes = files.filter { (it.resolution ?: 0) in 1..cfg.low_quality_max_resolution }.sumOf { it.size }
    val res = files.mapNotNull { it.resolution?.takeIf { r -> r != 0 } }
    item.min_resolution = res.minOrNull()
    item.max_resolution = res.maxOrNull()
}

/** plays == null means Tautulli failed this run: keep the cached play stats. */
private fun applyPlex(item: Item, meta: JsonObject?, plays: Map<String, PlayStats>?) {
    item.in_plex = meta != null
    if (meta == null) {
        item.plex_rating_key = null
        item.plex_section_key = null
        item.plex_added_at = null
        item.plex_view_count = 0
        item.plex_last_viewed_at = null
        item.episode_count = 0; item.watched_episode_count = 0
        if (plays != null) {
            item.play_count = 0; item.unique_viewers = 0; item.watched_episodes_all = 0
            item.last_played_at = null
        }
        return
    }
    val rk = meta.str("ratingKey")
    item.plex_rating_key = rk
    item.plex_section_key = meta.str(SECTION)
    item.plex_added_at = epoch(meta["addedAt"])
    item.plex_view_count = meta.int("viewCount") ?: 0
    item.plex_last_viewed_at = epoch(meta["lastViewedAt"])
    if (item.kind == "show") {
        item.episode_count = meta.int("leafCount") ?: 0
        item.watched_episode_count = meta.int("viewedLeafCount") ?: 0
    }
    if (plays == null) return
    val t = plays[rk]
    item.play_count = t?.plays ?: 0
    item.unique_viewers = t?.users?.size ?: 0
    item.last_played_at = t?.last
    item.watched_episodes_all = t?.eps?.size ?: 0
}

// ------------------------------------------------------------------ main

/** Runs a scan unless one is already running. */
suspend fun runScan(db: Db = Store.db, cfg: Settings = Config.current): JsonObject {
    if (!scanLock.tryLock()) return buildJsonObject { put("status", "already_running") }
    try {
        return doScan(db, cfg)
    } finally {
        scanLock.unlock()
    }
}

private suspend fun <T> guarded(name: String, warnings: MutableMap<String, String>, default: T, block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) { // one service down shouldn't sink the whole scan
        log.error("$name failed", e)
        synchronized(warnings) { warnings[name] = "${e::class.simpleName}: ${e.message}" }
        default
    }

private suspend fun doScan(db: Db, cfg: Settings): JsonObject {
    val run = ScanRun(started_at = Instant.now())
    db.saveScanRun(run)
    val runId = run.id!!

    val w = linkedMapOf<String, String>()
    val (radarr, sonarr, plex, tHist, torrents) = coroutineScope {
        val r = async { guarded("radarr", w, RadarrData()) { fetchRadarr(cfg) } }
        val s = async { guarded("sonarr", w, SonarrData()) { fetchSonarr(cfg) } }
        val p = async { guarded("plex", w, PlexData()) { fetchPlex(cfg) } }
        val t = async { guarded("tautulli", w, emptyList()) { fetchTautulli(cfg) } }
        val q = async { guarded("qbittorrent", w, emptyList()) { fetchQbit(cfg) } }
        Fetched(r.await(), s.await(), p.await(), t.await(), q.await())
    }

    // Plex indexes by external id
    fun index(list: List<JsonObject>): Map<Pair<String, String>, JsonObject> {
        val m = HashMap<Pair<String, String>, JsonObject>()
        for (x in list) for ((scheme, value) in PlexClient.externalIds(x)) m.putIfAbsent(scheme to value, x)
        return m
    }
    val plexMovieBy = index(plex.movies)
    val plexShowBy = index(plex.shows)

    val (plays, perUser) = aggregatePlays(tHist)
    val plexOk = "plex" !in w
    val tautulliOk = "tautulli" !in w
    val userPlaysByRk = perUser.entries.groupBy({ it.key.first }, { it.key.second to it.value })
    val (rByPath, rByMovie) = hashIndex(radarr.history, "movieId")
    val (sByPath, _) = hashIndex(sonarr.history, "seriesId")

    // When a service failed, keep its previously cached items instead of wiping them
    val skipKinds = buildSet { if ("radarr" in w) add("movie"); if ("sonarr" in w) add("show") }

    val counts = linkedMapOf<String, Int>()
    fun count(k: String, by: Int = 1) { counts[k] = (counts[k] ?: 0) + by }

    val summary = db.tx {
        val existing = items().associateByTo(LinkedHashMap()) { it.key }
        val newFiles = LinkedHashMap<String, List<MediaFile>>()

        fun upsert(key: String, kind: String, arrId: Long, title: String, fill: Item.() -> Unit): Item {
            val item = existing[key] ?: Item(key = key, kind = kind, arr_id = arrId, title = title)
            item.arr_id = arrId; item.title = title
            item.fill()
            item.last_seen_scan = runId
            saveItem(item)
            existing[key] = item
            return item
        }

        fun tagsJson(ids: JsonElement?, names: Map<Long, String>) =
            JsonArray(ids.arr.map { t -> JsonPrimitive(t.jsonPrimitive.longOrNull?.let { names[it] } ?: t.jsonPrimitive.content) }).toString()

        for (m in radarr.movies) {
            val mf = m.child("movieFile") ?: continue // monitored but not downloaded: nothing to prune
            val id = m.long("id")!!
            val key = "movie:$id"
            val item = upsert(key, "movie", id, m.str("title") ?: "") {
                year = m.int("year"); tmdb_id = m.long("tmdbId"); imdb_id = m.str("imdbId"); arr_path = m.str("path")
                arr_tags = tagsJson(m["tags"], radarr.tags)
            }
            val h = rByPath[mf.str("path")] ?: rByMovie[id]
            val files = listOf(fileRow(mf, h, cfg))
            applyFiles(item, files, cfg)
            if (plexOk) {
                val meta = m.str("tmdbId")?.let { plexMovieBy["tmdb" to it] } ?: m.str("imdbId")?.let { plexMovieBy["imdb" to it] }
                applyPlex(item, meta, if (tautulliOk) plays else null)
            }
            saveItem(item)
            newFiles[key] = files
            count("movies")
        }

        for (sr in sonarr.series) {
            val id = sr.long("id")!!
            val efs = sonarr.files[id].orEmpty()
            if (efs.isEmpty()) continue
            val key = "show:$id"
            val item = upsert(key, "show", id, sr.str("title") ?: "") {
                year = sr.int("year"); tvdb_id = sr.long("tvdbId"); imdb_id = sr.str("imdbId"); arr_path = sr.str("path")
                arr_tags = tagsJson(sr["tags"], sonarr.tags)
            }
            val files = efs.map { fileRow(it, sByPath[it.str("path")], cfg) }
            applyFiles(item, files, cfg)
            if (plexOk) {
                val meta = sr.str("tvdbId")?.let { plexShowBy["tvdb" to it] }
                applyPlex(item, meta, if (tautulliOk) plays else null)
            }
            saveItem(item)
            newFiles[key] = files
            count("shows")
        }

        // Files (and per-user plays): replace per item that was rescanned
        for ((key, files) in newFiles) {
            val item = existing.getValue(key)
            val itemId = item.id!!
            exec("DELETE FROM mediafile WHERE item_id = ?", itemId)
            for (f in files) {
                f.item_id = itemId
                save(Db.FILES, f)
                count("files")
                count("files_with_inode", if (f.inode != null) 1 else 0)
                count("files_with_torrent", if (f.torrent_hash != null) 1 else 0)
            }
            if (plexOk && tautulliOk) {
                exec("DELETE FROM userplay WHERE item_id = ?", itemId)
                item.plex_rating_key?.let { rk ->
                    for ((user, u) in userPlaysByRk[rk].orEmpty()) {
                        save(Db.USER_PLAYS, UserPlay(item_id = itemId, user = user, plays = u.plays,
                            last_played_at = u.last, episodes_watched = u.eps.size))
                    }
                }
            }
        }

        // Drop items that no longer exist in Radarr/Sonarr (unless that service failed this run)
        for (item in existing.values.toList()) {
            if (item.last_seen_scan != runId && item.kind !in skipKinds && item.id != null) {
                exec("DELETE FROM mediafile WHERE item_id = ?", item.id)
                exec("DELETE FROM userplay WHERE item_id = ?", item.id)
                exec("DELETE FROM item WHERE id = ?", item.id)
                count("removed")
            }
        }

        if ("qbittorrent" !in w) {
            exec("DELETE FROM torrent")
            for (t in torrents) {
                save(Db.TORRENTS, Torrent(
                    hash = t.str("hash")!!.lowercase(), name = t.str("name") ?: "", size = t.long("size") ?: 0,
                    ratio = t.double("ratio") ?: 0.0, seeding_time = t.long("seeding_time") ?: 0,
                    tracker = trackerHost(t.str("tracker")), // host only: the URL carries the passkey category = t.str("category")?.ifEmpty { null },
                    state = t.str("state"), added_on = epoch(t["added_on"]), content_path = t.str("content_path"),
                ))
            }
            counts["torrents"] = torrents.size
        }

        counts["plex_unmatched"] = newFiles.keys.count { !existing.getValue(it).in_plex }
        val summary = buildJsonObject {
            put("counts", JsonObject(counts.mapValues { JsonPrimitive(it.value) }))
            put("warnings", JsonObject(w.mapValues { JsonPrimitive(it.value) }))
        }
        run.finished_at = Instant.now()
        run.status = if (w.isEmpty()) "ok" else "partial"
        run.summary = summary.toString()
        saveScanRun(run)
        summary
    }

    log.info("scan {} finished: {}", runId, summary)
    return JsonObject(mapOf("scan_id" to JsonPrimitive(runId)) + summary)
}

private data class Fetched(val radarr: RadarrData, val sonarr: SonarrData, val plex: PlexData,
                           val tautulli: List<JsonObject>, val torrents: List<JsonObject>)

/** One scan from the command line: ./gradlew run --args=scan */
fun runScanCli() = runBlocking { println(prettyJson.encodeToString(JsonObject.serializer(), runScan())) }
