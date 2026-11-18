package karlstodian

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/*
 * Prune score: how strong a removal candidate each title is, 0-100 (higher = prune first).
 *
 * Each factor is scored 0-100 on its own, independent of the weights, so the UI can re-weight
 * live. The total is the weighted average of the factors. Some titles are never scored:
 * protected ones, ones Plex doesn't know (no age or watch data), and recently added ones.
 */

val FACTORS = listOf("size", "age", "watch", "quality")

// Watch factor = 60% "how much of it was watched" + 40% "how long since anyone watched"
const val UNWATCHED_SHARE = 0.6
const val STALE_SHARE = 0.4
const val MOVIE_FULL_PLAYS = 2 // a movie counts as fully "used" after this many plays

@Serializable
data class Factor(val score: Double, val note: String)

@Serializable
data class Score(val score: Double?, val excluded: String?, val factors: Map<String, Factor>)

/** An item plus its effective flags (app flag or Radarr/Sonarr tag). */
data class ScoreInput(val item: Item, val keepEffective: Boolean, val qualityExemptEffective: Boolean)

// Python-compatible rounding (exact binary value, ties to even), so scores and notes match the old app
private fun round1(x: Double) = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toDouble()
private fun Double.roundToLong(): Long = BigDecimal(this).setScale(0, RoundingMode.HALF_EVEN).toLong()
private fun fmt(pattern: String, vararg args: Any) = String.format(Locale.ROOT, pattern, *args)

private fun days(now: Instant, t: Instant?): Double? =
    t?.let { max(0.0, Duration.between(it, now).toMillis() / 86_400_000.0) }

fun humanDays(d: Double): String = when {
    d < 1 -> "today"
    d < 60 -> "${d.toInt()} days"
    d < 365 * 2 -> "${(d / 30.4).toInt()} months"
    else -> fmt("%.1f years", d / 365.25)
}

private fun sizeText(b: Long): String = when {
    b >= 1e12 -> fmt("%.2f TB", b / 1e12)
    b >= 1e10 -> fmt("%.0f GB", b / 1e9)
    else -> fmt("%.1f GB", b / 1e9)
}

private fun Item.plays() = max(play_count, plex_view_count)
private fun Item.watchedEpisodes() = max(watched_episodes_all, watched_episode_count)

/** 0..1. Shows: share of episodes anyone has finished. Movies: plays relative to MOVIE_FULL_PLAYS. */
fun watchedShare(i: Item): Double {
    if (i.kind == "show") {
        if (i.episode_count == 0) return 0.0
        return min(1.0, i.watchedEpisodes().toDouble() / i.episode_count)
    }
    return min(1.0, i.plays().toDouble() / MOVIE_FULL_PLAYS)
}

fun lastActivity(i: Item): Instant? = listOfNotNull(i.last_played_at, i.plex_last_viewed_at).maxOrNull()

/** Scores every item. Needs the whole library, since the size factor is a percentile. */
fun scoreItems(items: List<ScoreInput>, cfg: Settings, now: Instant = Instant.now()): Map<Long, Score> {
    val sizes = items.map { it.item.size_bytes }.sorted()
    val n = sizes.size
    val weights = FACTORS.associateWith { max(0.0, cfg.score_weights[it] ?: 0.0) }
    val wsum = weights.values.sum()
    val lowRes = cfg.low_quality_max_resolution

    return items.associate { input ->
        val i = input.item
        val f = linkedMapOf<String, Factor>()

        // size: where it ranks in the library (percentile), so one huge show doesn't flatten the rest
        val below = sizes.binarySearch(i.size_bytes).let { idx ->
            var k = if (idx >= 0) idx else -idx - 1
            while (k > 0 && sizes[k - 1] == i.size_bytes) k-- // bisect_left
            k
        }
        val pct = if (n > 1) below.toDouble() / (n - 1) else 1.0
        f["size"] = Factor(round1(100 * pct), "${sizeText(i.size_bytes)}, larger than ${(100 * pct).roundToLong()}% of the library")

        // age on Plex
        val onPlex = days(now, i.plex_added_at)
        f["age"] = if (onPlex == null) Factor(0.0, "Not in Plex")
        else Factor(round1(100 * min(1.0, onPlex / cfg.age_cap_days)),
            "On Plex for ${humanDays(onPlex)} (maxes out at ${humanDays(cfg.age_cap_days.toDouble())})")

        // watch activity
        val share = watchedShare(i)
        val since = days(now, lastActivity(i))
        val stale = if (since == null) 1.0 else min(1.0, since / cfg.recency_cap_days)
        val watch = 100 * (UNWATCHED_SHARE * (1 - share) + STALE_SHARE * stale)
        val what = if (i.kind == "show") {
            val eps = i.episode_count
            val p = 100 * share
            val pctS = if (p > 0 && p < 1) "<1%" else "${p.roundToLong()}%"
            if (eps > 0) "${min(i.watchedEpisodes(), eps)} of $eps episodes watched ($pctS)" else "No episode data"
        } else {
            val plays = i.plays()
            when {
                plays > 0 -> "Played $plays time${if (plays != 1) "s" else ""}"
                since != null -> "Never finished" // activity without a finished play: started and stopped
                else -> "Never played"
            }
        }
        val verb = if (i.kind == "movie" && i.plays() == 0) "started" else "watched"
        val whenText = when {
            since == null -> ""
            since >= 1 -> ", last $verb ${humanDays(since)} ago"
            else -> ", $verb today"
        }
        f["watch"] = Factor(round1(watch), what + whenText)

        // quality: share of the title's bytes at or below the low-quality line
        f["quality"] = if (input.qualityExemptEffective) Factor(0.0, "Exempt from the quality check")
        else {
            val low = i.low_quality_bytes
            val shareLow = if (i.size_bytes > 0) low.toDouble() / i.size_bytes else 0.0
            val note = when {
                shareLow >= 0.999 -> "All of it is ${lowRes}p or lower"
                low > 0 -> "${(100 * shareLow).roundToLong()}% of it is ${lowRes}p or lower"
                else -> "Nothing at ${lowRes}p or lower"
            }
            Factor(round1(100 * shareLow), note)
        }

        val excluded = when {
            input.keepEffective -> "Protected"
            !i.in_plex -> "Not in Plex"
            onPlex != null && onPlex < cfg.new_grace_days -> "Added ${humanDays(onPlex)} ago"
            else -> null
        }
        val total = if (excluded != null) null
        else if (wsum > 0) round1(FACTORS.sumOf { weights.getValue(it) * f.getValue(it).score } / wsum)
        else 0.0

        i.id!! to Score(total, excluded, f)
    }
}
