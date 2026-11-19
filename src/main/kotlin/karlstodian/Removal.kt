package karlstodian

import kotlinx.coroutines.CancellationException
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

    /** Put titles on the Leaving Soon list. Protected, unmatched and already-listed titles are refused. */
    fun mark(itemIds: List<Long>, db: Db = Store.db, cfg: Settings = Config.current, now: Instant = Instant.now()): MarkResult {
        val marked = mutableListOf<Removal>()
        val refused = linkedMapOf<Long, String>()
        db.tx {
            val active = activeByItem(db)
            for (id in itemIds.distinct()) {
                val item = db.item(id)
                val why = when {
                    item == null -> "not found"
                    keepEffective(item, cfg) -> "protected"
                    !item.in_plex -> "not in Plex"
                    id in active -> "already leaving"
                    else -> null
                }
                if (why != null) { refused[id] = why; continue }
                item!!
                val r = Removal(
                    item_key = item.key, item_id = id, kind = item.kind, title = item.title, year = item.year,
                    size_bytes = item.size_bytes, marked_at = now, remove_after = now.plus(Duration.ofDays(cfg.grace_days.toLong())),
                )
                db.saveRemoval(r)
                audit(db, r, "marked", "Leaving Soon; removal due ${r.remove_after}", now = now)
                marked += r
            }
        }
        return MarkResult(marked, refused)
    }

    /** Take a title off the list. Only titles that haven't started being removed can be kept. */
    fun cancel(removalId: Long, reason: String, db: Db = Store.db, now: Instant = Instant.now()): Removal {
        val r = db.removal(removalId) ?: throw HttpError(io.ktor.http.HttpStatusCode.NotFound, "Not Found")
        if (r.state !in setOf("leaving", "failed")) {
            throw HttpError(io.ktor.http.HttpStatusCode.Conflict, "It's already ${r.state.replace('_', ' ')}; it can't be kept now")
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
                val existing = p.collections(key)[cfg.leaving_soon_title]
                when {
                    existing == null -> if (want.isNotEmpty()) p.createCollection(key, cfg.leaving_soon_title, type, machineId, want)
                    want.isEmpty() -> p.deleteCollection(existing)
                    else -> {
                        val have = p.collectionItems(existing)
                        (want - have).takeIf { it.isNotEmpty() }?.let { p.addToCollection(existing, machineId, it) }
                        for (rk in have - want) p.removeFromCollection(existing, rk)
                    }
                }
            }
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

    /** Runs after every scan. */
    suspend fun afterScan(db: Db = Store.db, cfg: Settings = Config.current) {
        syncSafely(db, cfg)
    }
}
