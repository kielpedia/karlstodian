@file:Suppress("PropertyName")
@file:UseSerializers(InstantSerializer::class)

package karlstodian

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

// Database rows. The DB is a cache of scanned state plus the user's own flags.
// Property names match the column names (and the API's JSON keys).

/** A movie or a whole TV series: the unit the app scores and prunes. */
@Serializable
data class Item(
    var id: Long? = null,
    var key: String,             // "movie:12" (radarr id) / "show:5" (sonarr id)
    var kind: String,            // "movie" | "show"
    var title: String,
    var year: Int? = null,
    var tmdb_id: Long? = null,
    var tvdb_id: Long? = null,
    var imdb_id: String? = null,
    var arr_id: Long,
    var arr_path: String? = null,
    var arr_tags: String = "[]", // JSON list of tag names

    // Plex
    var in_plex: Boolean = false,
    var plex_rating_key: String? = null,
    var plex_section_key: String? = null, // library section, for the Leaving Soon collection
    var plex_added_at: Instant? = null,
    var plex_view_count: Int = 0,
    var plex_last_viewed_at: Instant? = null,
    var plex_thumb: String? = null,       // poster path on the Plex server, e.g. /library/metadata/1/thumb/1700000000
    var plex_art: String? = null,         // backdrop path

    // Episodes (shows only)
    var episode_count: Int = 0,          // episodes in Plex
    var watched_episode_count: Int = 0,  // Plex's count: the Plex admin account only
    var watched_episodes_all: Int = 0,   // distinct episodes any user finished (Tautulli)

    // Tautulli (across all users)
    var play_count: Int = 0,
    var unique_viewers: Int = 0,
    var last_played_at: Instant? = null,

    // Size and quality (aggregated over files)
    var size_bytes: Long = 0,
    var low_quality_bytes: Long = 0,     // bytes in files at or below the low-quality resolution
    var min_resolution: Int? = null,
    var max_resolution: Int? = null,

    // User flags: never overwritten by a scan
    var keep: Boolean = false,
    var quality_exempt: Boolean = false,
    var suggest_dismissed: Boolean = false, // "don't suggest this again"

    // Suggested for removal by the last suggestion run (see Suggest.kt); the owner approves or dismisses
    var suggested_at: Instant? = null,
    var suggest_reason: String? = null,

    var last_seen_scan: Long? = null,
)

@Serializable
data class MediaFile(
    var id: Long? = null,
    var item_id: Long = 0,
    var arr_file_id: Long,
    var path: String,                // as Radarr/Sonarr see it
    var local_path: String? = null,  // as this machine sees it
    var size: Long = 0,
    var resolution: Int? = null,
    var quality_name: String? = null,
    var video_codec: String? = null,
    var torrent_hash: String? = null,
    // Filesystem identity, for hardlink analysis (milestone 2)
    var device: Long? = null,
    var inode: Long? = null,
    var nlink: Long? = null,
)

@Serializable
data class Torrent(
    var hash: String,  // lowercase
    var name: String,
    var size: Long = 0,
    var ratio: Double = 0.0,
    var seeding_time: Long = 0, // seconds
    var tracker: String? = null,
    var category: String? = null,
    var state: String? = null,
    var added_on: Instant? = null,
    var content_path: String? = null,
)

/** Per-user watch stats for an item, from Tautulli. */
@Serializable
data class UserPlay(
    var id: Long? = null,
    var item_id: Long,
    var user: String,
    var plays: Int = 0,
    var episodes_watched: Int = 0, // distinct episodes finished (shows)
    var last_played_at: Instant? = null,
)

/**
 * A title on its way out: marked by the owner, waits out the grace period in the Leaving Soon
 * collection, then gets removed. Title/key are copied in because the item row disappears
 * after removal. state: leaving | removing | waiting_seed | done | cancelled | failed
 */
@Serializable
data class Removal(
    var id: Long? = null,
    var item_key: String,
    var item_id: Long? = null,
    var kind: String,
    var title: String,
    var year: Int? = null,
    var size_bytes: Long = 0,
    var state: String = "leaving",
    var marked_at: Instant,
    var remove_after: Instant,
    var finished_at: Instant? = null,
    var error: String? = null,
    var dry_run: Boolean = false,
    var watch_mark: Long = 0, // watch counters when marked; any increase means someone finished watching
)

/** A torrent belonging to a removal. state: pending (seeding until seed_until) | deleted | kept_shared | gone */
@Serializable
data class RemovalTorrent(
    var id: Long? = null,
    var removal_id: Long,
    var hash: String,
    var name: String,
    var tracker_host: String? = null,
    var size: Long = 0,
    var state: String = "pending",
    var seed_until: Instant? = null,
)

/** One line of the removal audit log. */
@Serializable
data class AuditEntry(
    var id: Long? = null,
    var at: Instant,
    var removal_id: Long? = null,
    var title: String? = null,
    var action: String,
    var detail: String = "",
    var dry_run: Boolean = false,
)

@Serializable
data class ScanRun(
    var id: Long? = null,
    var started_at: Instant,
    var finished_at: Instant? = null,
    var status: String = "running", // running | ok | partial
    var summary: String = "{}",     // JSON: counts and per-service warnings
)
