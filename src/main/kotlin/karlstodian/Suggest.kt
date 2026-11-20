package karlstodian

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.Locale

/*
 * Suggestions: after each scan, titles are flagged as removal candidates for the owner to
 * approve (which puts them in Leaving Soon) or dismiss. Nothing is marked automatically.
 *
 *  1. Every scored title at or above suggest_min_score, highest first.
 *  2. When free space is under free_space_goal_gb, the next-highest scorers too, until what's
 *     flagged plus what's already leaving would bring free space back up to the goal.
 *  At most max_suggestions at a time, so the list refills after each scan as titles are handled.
 */
object Suggestions {
    private val log = LoggerFactory.getLogger("karlstodian.suggest")

    /** A title kept (or watched) during its grace period isn't suggested again for this long. */
    const val RECENTLY_KEPT_DAYS = 90L

    /** Free space from the newest scan that could read it (null = unknown). */
    fun freeBytes(db: Db = Store.db): Pair<Long, Instant>? =
        db.scanRuns(20).firstNotNullOfOrNull { r ->
            val free = (json.parseToJsonElement(r.summary) as? JsonObject)?.get("free_bytes")?.jsonPrimitive?.longOrNull
            free?.let { it to (r.finished_at ?: r.started_at) }
        }

    fun goalBytes(cfg: Settings): Long = cfg.free_space_goal_gb * 1_000_000_000L

    /** Bytes on their way out: titles in Leaving Soon (not yet deleted). */
    fun leavingBytes(db: Db): Long = db.removals("WHERE state IN ('leaving','removing')").sumOf { it.size_bytes }

    /** Recompute the suggestions. Returns how many titles are suggested now. */
    fun refresh(db: Db = Store.db, cfg: Settings = Config.current, now: Instant = Instant.now()): Int {
        val items = db.items()
        val scores = scoreItems(items.map { inputOf(it, cfg) }, cfg, now)
        val active = Removals.activeByItem(db).keys
        val recentlyKept = db.removals("WHERE state = 'cancelled' AND finished_at > ?",
            Table.toDb(now.minus(Duration.ofDays(RECENTLY_KEPT_DAYS)))).mapNotNull { it.item_id }.toSet()

        val candidates = items
            .filter { i -> scores[i.id]?.score != null && i.id !in active && !i.suggest_dismissed && i.id !in recentlyKept }
            .sortedWith(compareByDescending<Item> { scores.getValue(it.id!!).score!! }.thenByDescending { it.size_bytes })

        val chosen = linkedMapOf<Long, String>()
        if (cfg.auto_suggest) {
            for (i in candidates) {
                if (chosen.size >= cfg.max_suggestions) break
                val s = scores.getValue(i.id!!).score!!
                if (s >= cfg.suggest_min_score) chosen[i.id!!] = "Score ${fmt(s)} is at or above ${fmt(cfg.suggest_min_score)}"
            }
            val goal = goalBytes(cfg)
            val free = freeBytes(db)?.first
            if (goal > 0 && free != null) {
                var expected = free + leavingBytes(db) + candidates.filter { it.id in chosen }.sumOf { it.size_bytes }
                for (i in candidates) {
                    if (expected >= goal || chosen.size >= cfg.max_suggestions) break
                    if (i.id in chosen) continue
                    chosen[i.id!!] = "Free space (${Removals.humanBytes(free)}) is under the ${Removals.humanBytes(goal)} goal; " +
                        "one of the highest scores (${fmt(scores.getValue(i.id!!).score!!)})"
                    expected += i.size_bytes
                }
            }
        }

        db.tx {
            for (i in items) {
                val reason = chosen[i.id]
                if (reason == i.suggest_reason && (reason == null) == (i.suggested_at == null)) continue
                i.suggested_at = if (reason == null) null else i.suggested_at ?: now
                i.suggest_reason = reason
                saveItem(i)
            }
        }
        log.info("{} title(s) suggested for removal", chosen.size)
        return chosen.size
    }

    /** Take a title off the suggestions (it was marked, protected or dismissed). */
    fun clear(i: Item) {
        i.suggested_at = null
        i.suggest_reason = null
    }

    /** What the Library page shows about suggestions. */
    fun info(db: Db = Store.db, cfg: Settings = Config.current): JsonObject = buildJsonObject {
        put("enabled", cfg.auto_suggest)
        put("min_score", cfg.suggest_min_score)
        put("max", cfg.max_suggestions)
        put("goal_bytes", goalBytes(cfg))
        val free = freeBytes(db)
        put("free_bytes", free?.first)
        put("free_at", free?.second?.toString())
        put("leaving_bytes", leavingBytes(db))
    }

    private fun fmt(x: Double) = String.format(Locale.ROOT, "%.0f", x)
}
