package karlstodian

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/*
 * The removal workflow. The owner marks titles; they sit in a "Leaving Soon" Plex collection
 * for the grace period (so other users can watch them first), then get removed.
 */
object Removals {
    private val log = LoggerFactory.getLogger("karlstodian.removal")

    /** States in which a title is still on its way out (one per title at a time). */
    val ACTIVE = listOf("leaving", "removing", "waiting_seed")
    private val ACTIVE_SQL = ACTIVE.joinToString(",") { "'$it'" }

    /** One removal run at a time (hourly timer, after scans, "Remove now"). */
    private val runLock = Mutex()

    /** Every finished-watching counter for an item. Any increase means someone finished watching something. */
    fun watchCounter(i: Item): Long =
        i.play_count.toLong() + i.plex_view_count + i.watched_episodes_all + i.watched_episode_count

    /** Last Plex collection sync problem, shown in the UI (null = fine). */
    @Volatile var plexSyncError: String? = null
        private set

    fun active(db: Db = Store.db): List<Removal> = db.removals("WHERE state IN ($ACTIVE_SQL) ORDER BY remove_after, id")
    fun activeByItem(db: Db = Store.db): Map<Long, Removal> =
        active(db).filter { it.item_id != null }.associateBy { it.item_id!! }

    fun audit(db: Db, r: Removal?, action: String, detail: String = "", dryRun: Boolean = false, now: Instant = Instant.now()) {
        db.addAudit(AuditEntry(at = now, removal_id = r?.id, title = r?.title, action = action, detail = detail, dry_run = dryRun))
    }

    data class MarkResult(val marked: List<Removal>, val refused: Map<Long, String>)

    /**
     * Put titles on the Leaving Soon list. Protected, unmatched and already-listed titles are refused.
     * [immediate] is for titles that aren't in Plex (nobody can watch them, so there's no grace period):
     * only those are accepted, due right away; the caller removes them.
     */
    fun mark(
        itemIds: List<Long>, db: Db = Store.db, cfg: Settings = Config.current, now: Instant = Instant.now(),
        immediate: Boolean = false,
    ): MarkResult {
        val marked = mutableListOf<Removal>()
        val refused = linkedMapOf<Long, String>()
        db.tx {
            val active = activeByItem(db)
            for (id in itemIds.distinct()) {
                val item = db.item(id)
                val why = when {
                    item == null -> "not found"
                    keepEffective(item, cfg) -> "protected"
                    !immediate && !item.in_plex -> "not in Plex"
                    immediate && item.in_plex -> "in Plex, so it goes through Leaving Soon"
                    id in active -> "already leaving"
                    else -> null
                }
                if (why != null) { refused[id] = why; continue }
                item!!
                val r = Removal(
                    item_key = item.key, item_id = id, kind = item.kind, title = item.title, year = item.year,
                    size_bytes = item.size_bytes, marked_at = now,
                    remove_after = if (immediate) now else now.plus(Duration.ofDays(cfg.grace_days.toLong())),
                    watch_mark = watchCounter(item),
                )
                db.saveRemoval(r)
                val suggested = item.suggest_reason?.let { "Suggested: $it. " } ?: ""
                if (item.suggested_at != null) { Suggestions.clear(item); db.saveItem(item) }
                audit(db, r, "marked", suggested + if (immediate) "Not in Plex; removing it now" else "Leaving Soon; removal due ${r.remove_after}", now = now)
                marked += r
            }
        }
        return MarkResult(marked, refused)
    }

    /** Take a title off the list. Only titles that haven't started being removed can be kept. */
    fun cancel(removalId: Long, reason: String, db: Db = Store.db, now: Instant = Instant.now()): Removal {
        val r = db.removal(removalId) ?: throw HttpError(HttpStatusCode.NotFound, "Not Found")
        if (r.state !in setOf("leaving", "failed")) {
            throw HttpError(HttpStatusCode.Conflict, "It's already ${r.state.replace('_', ' ')}; it can't be kept now")
        }
        r.state = "cancelled"
        r.finished_at = now
        db.saveRemoval(r)
        audit(db, r, "cancelled", reason, now = now)
        return r
    }

    /** Cancel the active removal of an item, if any (e.g. it was just protected). */
    fun cancelForItem(itemId: Long, reason: String, db: Db = Store.db) {
        activeByItem(db)[itemId]?.takeIf { it.state == "leaving" }?.let { cancel(it.id!!, reason, db) }
    }

    /**
     * Make each library section's Leaving Soon collection hold exactly the titles that are
     * leaving. Creates the collection when needed and deletes it when it would be empty.
     */
    suspend fun syncLeavingSoon(db: Db = Store.db, cfg: Settings = Config.current) {
        if (cfg.plex_url.isEmpty()) return
        val wanted: Map<String, Set<String>> = db.removals("WHERE state = 'leaving'")
            .mapNotNull { r -> r.item_id?.let(db::item) }
            .filter { it.plex_section_key != null && it.plex_rating_key != null }
            .groupBy { it.plex_section_key!! }
            .mapValues { (_, items) -> items.map { it.plex_rating_key!! }.toSet() }
        PlexClient(cfg.plex_url, cfg.plex_token).use { p ->
            val machineId = p.machineId()
            for (sec in p.sections()) {
                val type = when (sec.str("type")) { "movie" -> 1; "show" -> 2; else -> continue }
                val key = sec.str("key") ?: continue
                val want = wanted[key].orEmpty()
                val title = collectionTitle(cfg, sec.str("title"))
                val collections = p.collections(key)
                // A collection from before titles named the library ("Leaving Soon") is renamed, keeping its pins.
                val existing = collections[title] ?: collections[cfg.leaving_soon_title]?.also {
                    if (want.isNotEmpty() && title != cfg.leaving_soon_title) p.renameCollection(key, it, title)
                }
                when {
                    existing == null -> if (want.isNotEmpty()) p.createCollection(key, title, type, machineId, want)
                    want.isEmpty() -> p.deleteCollection(existing)
                    else -> {
                        val have = p.collectionItems(existing)
                        (want - have).takeIf { it.isNotEmpty() }?.let { p.addToCollection(existing, machineId, it) }
                        for (rk in have - want) p.removeFromCollection(existing, rk)
                    }
                }
                if (want.isNotEmpty()) pin(p, key, existing ?: p.collections(key)[title], cfg)
            }
        }
    }

    /** "Leaving Soon: Movies": on Plex's Home the rows from different libraries look alike otherwise. */
    fun collectionTitle(cfg: Settings, sectionTitle: String?) =
        if (sectionTitle.isNullOrBlank()) cfg.leaving_soon_title else "${cfg.leaving_soon_title}: $sectionTitle"

    /**
     * Shows the collection as a row on Home (yours and your friends') and the library's Recommended tab.
     * Best effort: a collection that can't be pinned still works from the library's Collections tab.
     */
    private suspend fun pin(p: PlexClient, sectionKey: String, collectionKey: String?, cfg: Settings) {
        if (collectionKey == null) return
        try {
            if (!p.collectionPinned(sectionKey, collectionKey)) p.pinCollection(sectionKey, collectionKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Couldn't pin the ${cfg.leaving_soon_title} collection to Home in Plex section $sectionKey: ${friendly(e)}")
        }
    }

    /** Sync, but never fail the caller: a Plex hiccup is shown in the UI and retried next time. */
    suspend fun syncSafely(db: Db = Store.db, cfg: Settings = Config.current) {
        try {
            syncLeavingSoon(db, cfg)
            plexSyncError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            plexSyncError = "Couldn't update the ${cfg.leaving_soon_title} collection in Plex: ${friendly(e)}"
            log.warn(plexSyncError, e)
        }
    }

    /** Runs after every scan: the scan brought fresh watch data, so check the list now. */
    suspend fun afterScan(db: Db = Store.db, cfg: Settings = Config.current) {
        processDue(db, cfg)
        syncSafely(db, cfg)
    }

    // ------------------------------------------------------------------ the engine

    /**
     * The periodic run:
     *  1. titles someone finished watching during the grace period stay (if cancel_on_watch)
     *  2. titles whose grace period is over are removed (at most max_removals_per_run)
     *  3. torrents that were still seeding are deleted once they've seeded long enough
     */
    suspend fun processDue(db: Db = Store.db, cfg: Settings = Config.current, now: Instant = Instant.now()): JsonObject =
        runLock.withLock {
            var cancelled = 0
            var removed = 0
            val leaving = db.removals("WHERE state = 'leaving' ORDER BY remove_after, id")
            val stillLeaving = leaving.filter { r ->
                val item = r.item_id?.let(db::item)
                when {
                    item == null -> { cancel(r.id!!, "No longer in Radarr/Sonarr, so there's nothing to remove", db, now); cancelled++; false }
                    keepEffective(item, cfg) -> { cancel(r.id!!, "Protected", db, now); cancelled++; false }
                    cfg.cancel_on_watch && watchCounter(item) > r.watch_mark -> {
                        cancel(r.id!!, "Watched during the grace period", db, now); cancelled++; false
                    }
                    else -> true
                }
            }
            for (r in stillLeaving.filter { !it.remove_after.isAfter(now) }.take(cfg.max_removals_per_run)) {
                removeTitle(r, db, cfg, now, early = false)
                removed++
            }
            val seeding = finishSeeding(db, cfg, now)
            buildJsonObject { put("cancelled", cancelled); put("removed", removed); put("torrents_deleted", seeding) }
        }

    /** "Remove now" from the UI: skips the rest of the grace period (still obeys dry run). */
    suspend fun removeNow(removalId: Long, db: Db = Store.db, cfg: Settings = Config.current, now: Instant = Instant.now()): Removal =
        runLock.withLock {
            val r = db.removal(removalId) ?: throw HttpError(HttpStatusCode.NotFound, "Not Found")
            if (r.state !in setOf("leaving", "failed")) throw HttpError(HttpStatusCode.Conflict, "It's already ${r.state.replace('_', ' ')}")
            val item = r.item_id?.let(db::item)
            if (item != null && keepEffective(item, cfg)) throw HttpError(HttpStatusCode.Conflict, "It's protected")
            removeTitle(r, db, cfg, now, early = true)
            db.removal(removalId)!!
        }

    private fun daysText(seconds: Long) = "%.1f days".format(java.util.Locale.ROOT, seconds / 86400.0)

    /** Remove one title: Radarr/Sonarr first (unmonitor + delete files), then its torrents. Each step is logged. */
    private suspend fun removeTitle(r: Removal, db: Db, cfg: Settings, now: Instant, early: Boolean) {
        val dry = cfg.dry_run
        fun log(action: String, detail: String) = audit(db, r, action, detail, dry, now)
        r.state = "removing"; r.error = null; r.dry_run = dry
        db.saveRemoval(r)
        log("removing", if (early) "Removed early by the owner" else "Grace period over")
        try {
            val item = r.item_id?.let(db::item)
            val arrId = item?.arr_id ?: r.item_key.substringAfter(':').toLong()

            // 1. Radarr / Sonarr: stop searching for it, then delete its files
            val app = if (r.kind == "movie") "Radarr" else "Sonarr"
            val client: ArrClient? = if (r.kind == "movie") cfg.radarr_url.takeIf { it.isNotEmpty() }?.let { RadarrClient(it, cfg.radarr_api_key) }
                else cfg.sonarr_url.takeIf { it.isNotEmpty() }?.let { SonarrClient(it, cfg.sonarr_api_key) }
            client ?: throw IllegalStateException("$app isn't set up")
            client.use { c ->
                if (c.find(arrId) == null) log("arr", "Already gone from $app")
                else {
                    val files = c.fileIds(arrId)
                    if (dry) log("arr", "Would unmonitor it in $app and delete ${files.size} file${if (files.size == 1) "" else "s"} (${humanBytes(r.size_bytes)})")
                    else {
                        c.unmonitor(arrId)
                        c.deleteFiles(files)
                        log("arr", "Unmonitored in $app and deleted ${files.size} file${if (files.size == 1) "" else "s"} (${humanBytes(r.size_bytes)})")
                    }
                }
            }

            // 2. Torrents: delete with their data once they've seeded long enough
            val hashes = item?.let { i -> db.files(i.id!!).mapNotNull { it.torrent_hash }.toSet() }.orEmpty()
            val known = db.removalTorrents(r.id!!).associateBy { it.hash }
            val rows = hashes.map { h ->
                known[h] ?: db.torrent(h).let { t ->
                    RemovalTorrent(removal_id = r.id!!, hash = h, name = t?.name ?: h, tracker_host = trackerHost(t?.tracker), size = t?.size ?: 0)
                }
            }
            val alsoLeaving = activeByItem(db).keys
            for (rt in rows) {
                // Another title still in the library uses this torrent: leave it seeding for that one
                val keptBy = db.query(
                    "SELECT DISTINCT i.id, i.title FROM mediafile f JOIN item i ON i.id = f.item_id WHERE f.torrent_hash = ? AND f.item_id != ?",
                    rt.hash, item!!.id,
                ) { it.getLong(1) to it.getString(2) }.filter { (id, _) -> id !in alsoLeaving }.map { it.second }
                if (keptBy.isNotEmpty()) {
                    rt.state = "kept_shared"
                    log("torrent", "Kept ${rt.name}: also used by ${keptBy.joinToString(", ")}")
                    if (!dry) db.saveRemovalTorrent(rt)
                }
            }
            val pending = rows.filter { it.state == "pending" }
            settleTorrents(r, pending, db, cfg, now, dry)

            // 3. Plex notices the files are gone on its next library scan; ask for one now
            if (!dry && cfg.plex_url.isNotEmpty()) item?.plex_section_key?.let { key ->
                try { PlexClient(cfg.plex_url, cfg.plex_token).use { it.refreshSection(key) } }
                catch (e: Exception) { if (e is CancellationException) throw e; log("plex", "Couldn't ask Plex to rescan: ${friendly(e)}") }
            }

            r.state = if (!dry && pending.any { it.state == "pending" }) "waiting_seed" else "done"
            if (r.state == "done") r.finished_at = now
            db.saveRemoval(r)
            log(r.state, if (r.state == "done") (if (dry) "Dry run: nothing was deleted" else "Removed") else "Removed; torrents still seeding")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            r.state = "failed"; r.error = friendly(e)
            db.saveRemoval(r)
            log("failed", r.error!!)
            log.warn("removing ${r.title} failed", e)
        }
    }

    /**
     * Delete pending torrents that have seeded long enough; the rest get a seed_until date.
     * Torrents qBittorrent no longer has are marked gone. Returns how many were deleted.
     */
    private suspend fun settleTorrents(r: Removal, pending: List<RemovalTorrent>, db: Db, cfg: Settings, now: Instant, dry: Boolean): Int {
        if (pending.isEmpty()) return 0
        fun log(action: String, detail: String) = audit(db, r, action, detail, dry, now)
        if (cfg.qbit_url.isEmpty()) throw IllegalStateException("qBittorrent isn't set up")
        var deleted = 0
        QbitClient(cfg.qbit_url, cfg.qbit_username, cfg.qbit_password).use { q ->
            val live = q.torrents(pending.map { it.hash }).associateBy { it.str("hash")!!.lowercase() }
            val ready = mutableListOf<RemovalTorrent>()
            for (rt in pending) {
                val t = live[rt.hash]
                if (t == null) {
                    rt.state = "gone"
                    log("torrent", "${rt.name} is no longer in qBittorrent")
                    continue
                }
                val need = cfg.seedMinDays(rt.tracker_host) * 86400L
                val seeded = t.long("seeding_time") ?: 0
                if (seeded >= need) ready += rt
                else {
                    rt.seed_until = now.plusSeconds(need - seeded)
                    log("torrent", "${if (dry) "Would keep" else "Keeping"} ${rt.name} seeding until ${rt.seed_until} " +
                        "(seeded ${daysText(seeded)} of ${daysText(need)}${rt.tracker_host?.let { " for $it" } ?: ""})")
                }
            }
            if (ready.isNotEmpty()) {
                if (dry) ready.forEach { log("torrent", "Would delete ${it.name} and its data (${humanBytes(it.size)})") }
                else {
                    q.deleteWithData(ready.map { it.hash })
                    ready.forEach { it.state = "deleted"; log("torrent", "Deleted ${it.name} and its data (${humanBytes(it.size)})") }
                    deleted = ready.size
                }
            }
        }
        if (!dry) pending.forEach { db.saveRemovalTorrent(it) }
        return deleted
    }

    /** Step 3 of the run: torrents of removed titles that were still seeding. */
    private suspend fun finishSeeding(db: Db, cfg: Settings, now: Instant): Int {
        var deleted = 0
        for (r in db.removals("WHERE state = 'waiting_seed' ORDER BY id")) {
            val pending = db.removalTorrents(r.id!!).filter { it.state == "pending" }
            if (pending.any { it.seed_until == null || !it.seed_until!!.isAfter(now) }) {
                try {
                    deleted += settleTorrents(r, pending, db, cfg, now, dry = false)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    audit(db, r, "torrent", "Couldn't check torrents: ${friendly(e)}", now = now)
                    continue
                }
            }
            if (db.removalTorrents(r.id!!).none { it.state == "pending" }) {
                r.state = "done"; r.finished_at = now
                db.saveRemoval(r)
                audit(db, r, "done", "Torrents finished seeding and were deleted", now = now)
            }
        }
        return deleted
    }

    fun humanBytes(b: Long): String = when {
        b >= 1e12 -> "%.2f TB".format(java.util.Locale.ROOT, b / 1e12)
        b >= 1e9 -> "%.1f GB".format(java.util.Locale.ROOT, b / 1e9)
        else -> "%.0f MB".format(java.util.Locale.ROOT, b / 1e6)
    }
}
