package karlstodian

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions

/**
 * Settings. Saved through the settings page in settings.json next to the database.
 * Environment variables (prefix PRUNER_ or KARLSTODIAN_, also read from .env) win over
 * settings.json: a field set that way is locked on the settings page and never written to
 * settings.json, so keys can come from a Kubernetes Secret and be rotated there.
 * KARLSTODIAN_DATA=/data puts the database (and so settings.json) in that folder.
 *
 * Field names are snake_case on purpose: they are the JSON keys in settings.json, the env var
 * suffixes and the API's field names, all shared with the earlier Python version.
 */
@Suppress("PropertyName")
@Serializable
data class Settings(
    val db_path: String = "data/pruner.db",

    val plex_url: String = "",
    val plex_token: String = "",
    val tautulli_url: String = "",
    val tautulli_api_key: String = "",
    val radarr_url: String = "",
    val radarr_api_key: String = "",
    val sonarr_url: String = "",
    val sonarr_api_key: String = "",
    val qbit_url: String = "",
    val qbit_username: String = "",
    val qbit_password: String = "",

    /** How Radarr/Sonarr see paths -> where the same files are mounted here. [[remote, local], ...] */
    val path_maps: List<List<String>> = emptyList(),

    /** Radarr/Sonarr tags that set flags without touching the app */
    val keep_tag: String = "prune-keep",
    val quality_ok_tag: String = "prune-quality-ok",

    /** Anything at or below this vertical resolution counts as low quality */
    val low_quality_max_resolution: Int = 720,

    // Scoring (see Scoring.kt)
    val score_weights: Map<String, Double> = mapOf("age" to 20.0, "quality" to 10.0, "size" to 30.0, "watch" to 40.0),
    val age_cap_days: Int = 730,       // age factor maxes out after this long on Plex
    val recency_cap_days: Int = 365,   // "not watched for this long" counts as fully stale
    val new_grace_days: Int = 60,      // titles added more recently aren't scored

    // Removal (see Removal.kt)
    val grace_days: Int = 14,              // time in Leaving Soon before a title is removed
    val dry_run: Boolean = true,           // log what would be deleted instead of deleting
    val cancel_on_watch: Boolean = true,   // a finished play during the grace period takes it off the list
    val seed_min_days: Int = 14,           // keep seeding a removed title's torrent at least this long...
    val seed_rules: Map<String, Int> = emptyMap(), // ...or this long for a tracker host: {"tracker.example": 30}
    val leaving_soon_title: String = "Leaving Soon", // Plex collection name
    val max_removals_per_run: Int = 25,    // safety cap per automatic run

    // Suggestions (see Suggest.kt): titles flagged after each scan for the owner to approve
    val auto_suggest: Boolean = true,
    val suggest_min_score: Double = 75.0,  // flag titles scoring at least this
    val free_space_goal_gb: Int = 0,       // when free space is under this, flag more top scorers (0 = no goal)
    val max_suggestions: Int = 10,         // at most this many flagged at a time

    val scan_cron: String = "0 4 * * *", // nightly at 04:00; empty string disables
    val verify_ssl: Boolean = true,
    val http_timeout: Double = 60.0,
) {
    /** Throws SettingsError when a value is out of range. */
    fun validated(): Settings {
        val unknown = score_weights.keys - FACTORS.toSet()
        if (unknown.isNotEmpty()) throw SettingsError("score_weights: unknown factor(s): ${unknown.sorted().joinToString(", ")}")
        if (score_weights.values.any { it !in 0.0..100.0 }) throw SettingsError("score_weights: weights must be between 0 and 100")
        if (age_cap_days < 1) throw SettingsError("age_cap_days: must be at least 1 day")
        if (recency_cap_days < 1) throw SettingsError("recency_cap_days: must be at least 1 day")
        if (new_grace_days < 0) throw SettingsError("new_grace_days: can't be negative")
        if (path_maps.any { it.size != 2 }) throw SettingsError("path_maps: each entry must be [remote, local]")
        if (grace_days < 0) throw SettingsError("grace_days: can't be negative")
        if (seed_min_days < 0) throw SettingsError("seed_min_days: can't be negative")
        if (seed_rules.values.any { it < 0 }) throw SettingsError("seed_rules: days can't be negative")
        if (seed_rules.keys.any { it.isBlank() }) throw SettingsError("seed_rules: tracker host can't be blank")
        if (leaving_soon_title.isBlank()) throw SettingsError("leaving_soon_title: can't be blank")
        if (max_removals_per_run < 1) throw SettingsError("max_removals_per_run: must be at least 1")
        if (suggest_min_score !in 0.0..100.0) throw SettingsError("suggest_min_score: must be between 0 and 100")
        if (free_space_goal_gb < 0) throw SettingsError("free_space_goal_gb: can't be negative")
        if (max_suggestions < 1) throw SettingsError("max_suggestions: must be at least 1")
        return copy(
            score_weights = FACTORS.sorted().associateWith { score_weights[it] ?: 0.0 },
            seed_rules = seed_rules.mapKeys { trackerHost(it.key) ?: it.key.trim().lowercase() },
        )
    }

    /** Minimum seeding time for a torrent from this tracker host. */
    fun seedMinDays(host: String?): Int = host?.let { seed_rules[it] } ?: seed_min_days

    /** Translate an *arr path into a path readable from this machine (null = not mapped). */
    fun mapPath(remote: String): String? {
        if (path_maps.isEmpty()) return remote
        for ((src0, dst) in path_maps) {
            val src = src0.trimEnd('/')
            if (remote == src || remote.startsWith("$src/")) return dst.trimEnd('/') + remote.substring(src.length)
        }
        return null
    }

    fun get(field: String): JsonElement = json.encodeToJsonElement(this).jsonObject[field] ?: JsonNull
}

class SettingsError(message: String) : Exception(message)

/** service -> (url field, other fields..., secret field last) */
val SERVICES: Map<String, List<String>> = linkedMapOf(
    "plex" to listOf("plex_url", "plex_token"),
    "tautulli" to listOf("tautulli_url", "tautulli_api_key"),
    "radarr" to listOf("radarr_url", "radarr_api_key"),
    "sonarr" to listOf("sonarr_url", "sonarr_api_key"),
    "qbittorrent" to listOf("qbit_url", "qbit_username", "qbit_password"),
)
val SECRET_FIELDS = setOf("plex_token", "tautulli_api_key", "radarr_api_key", "sonarr_api_key", "qbit_password")
val GENERAL_FIELDS = listOf(
    "path_maps", "keep_tag", "quality_ok_tag", "low_quality_max_resolution", "scan_cron", "verify_ssl",
    "score_weights", "age_cap_days", "recency_cap_days", "new_grace_days",
    "grace_days", "dry_run", "cancel_on_watch", "seed_min_days", "seed_rules", "leaving_soon_title", "max_removals_per_run",
    "auto_suggest", "suggest_min_score", "free_space_goal_gb", "max_suggestions",
)

/** "https://tracker.example:443/abc123passkey/announce" -> "tracker.example". Never keep the passkey. */
fun trackerHost(tracker: String?): String? {
    val t = tracker?.trim().orEmpty()
    if (t.isEmpty()) return null
    if ("://" !in t) return t.substringBefore('/').substringBefore(':').lowercase().ifEmpty { null }
    return try { URI(t).host?.lowercase() } catch (_: Exception) { null }
}
val EDITABLE: List<String> = SERVICES.values.flatten() + GENERAL_FIELDS

/** Holds the live settings. Everything reads `Config.current`, so a save takes effect at once. */
object Config {
    // declared before `current`: load() below uses them while the object initializes
    /** Both prefixes work; KARLSTODIAN_ wins when both set the same field. */
    private val PREFIXES = listOf("PRUNER_", "KARLSTODIAN_")

    /** Editable fields set by environment variables: they win over settings.json and can't be changed in the UI. */
    @Volatile
    var envFields: Set<String> = emptySet()
        private set

    @Volatile
    var current: Settings = load()

    /** Re-read env/.env and settings.json (tests use this after changing the environment). */
    fun reload(env: Map<String, String> = System.getenv()) {
        current = load(env)
    }

    fun settingsFile(s: Settings = current): Path = Paths.get(s.db_path).toAbsolutePath().parent.resolve("settings.json")

    private fun load(env: Map<String, String> = System.getenv()): Settings {
        val (base, fromEnvFields) = fromEnv(dotEnv() + env.filterKeys { k -> PREFIXES.any { k.startsWith(it) } })
        envFields = fromEnvFields intersect EDITABLE.toSet()
        val file = settingsFile(base)
        if (!Files.exists(file)) return base
        val saved = json.parseToJsonElement(Files.readString(file)).jsonObject.filterKeys { it in EDITABLE && it !in envFields }
        return decode(JsonObject(json.encodeToJsonElement(base).jsonObject + saved))
    }

    /** Editable settings for the browser. Secrets are replaced by whether they're set. */
    fun publicView(s: Settings = current): JsonObject = buildJsonObject {
        val all = json.encodeToJsonElement(s).jsonObject
        for (f in EDITABLE) {
            if (f in SECRET_FIELDS) put(f, buildJsonObject { put("set", all[f]!!.jsonPrimitive.content.isNotEmpty()) })
            else put(f, all[f]!!)
        }
    }

    /**
     * Validate partial updates against the current settings and return the result (without applying it).
     *  - blank secrets mean "keep the saved one"; list a field in `_clear` to remove it
     *  - changing where a service lives requires re-entering its secret, so a changed URL
     *    can't be used to send the saved key somewhere else
     */
    fun merge(updates0: JsonObject): Settings {
        val updates = updates0.toMutableMap()
        val clear = (updates.remove("_clear") as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
        val unknown = (updates.keys + clear) - EDITABLE.toSet()
        if (unknown.isNotEmpty()) throw SettingsError("Unknown setting(s): ${unknown.sorted().joinToString(", ")}")

        val cur = current
        val merged = json.encodeToJsonElement(cur).jsonObject.toMutableMap()
        for ((k, v0) in updates) {
            if (k in envFields) continue // set by an environment variable
            var v = v0
            if (k in SECRET_FIELDS && (v is JsonNull || (v is JsonPrimitive && v.isString && v.content.isEmpty()))) continue
            if (v is JsonPrimitive && v.isString) {
                var s = v.content.trim()
                if (k.endsWith("_url")) {
                    s = s.trimEnd('/')
                    if (s.isNotEmpty() && !s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
                }
                v = JsonPrimitive(s)
            }
            merged[k] = v
        }
        for (k in (clear intersect SECRET_FIELDS) - envFields) merged[k] = JsonPrimitive("")

        for ((svc, fields) in SERVICES) {
            val urlF = fields.first()
            val secretF = fields.last()
            val newUrl = merged[urlF]!!.jsonPrimitive.content
            val urlChanged = urlF in updates && normUrl(newUrl) != normUrl(cur.get(urlF).jsonPrimitive.content)
            val newSecret = (updates[secretF] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (urlChanged && newUrl.isNotEmpty() && cur.get(secretF).jsonPrimitive.content.isNotEmpty() && newSecret.isEmpty()) {
                throw SettingsError("$svc: re-enter the ${if (svc == "qbittorrent") "password" else "key"} when changing the URL")
            }
        }
        return decode(JsonObject(merged))
    }

    /** Swap new values in and (by default) save them to settings.json, readable by the owner only. */
    fun apply(new: Settings, persist: Boolean = true) {
        current = current.let { cur ->
            val all = json.encodeToJsonElement(cur).jsonObject.toMutableMap()
            val n = json.encodeToJsonElement(new).jsonObject
            for (f in EDITABLE) all[f] = n[f]!!
            json.decodeFromJsonElement<Settings>(JsonObject(all))
        }
        if (!persist) return
        val path = settingsFile()
        Files.createDirectories(path.parent)
        val all = json.encodeToJsonElement(current).jsonObject
        val data = JsonObject((EDITABLE - envFields).associateWith { all[it]!! }) // env values stay out of the file
        val tmp = path.resolveSibling("settings.tmp")
        Files.deleteIfExists(tmp)
        createPrivateFile(tmp)
        Files.writeString(tmp, prettyJson.encodeToString(JsonObject.serializer(), data))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun createPrivateFile(p: Path) {
        try {
            Files.createFile(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            Files.createFile(p) // Windows dev machine: no POSIX modes
        }
    }

    private fun decode(obj: JsonObject): Settings = try {
        json.decodeFromJsonElement<Settings>(obj).validated()
    } catch (e: SettingsError) {
        throw e
    } catch (e: IllegalArgumentException) { // kotlinx.serialization type errors
        throw SettingsError(e.message?.lineSequence()?.first() ?: "invalid value")
    }

    /** PRUNER_* / KARLSTODIAN_* variables -> Settings, plus which fields they set. Lists/maps are JSON. */
    private fun fromEnv(vars: Map<String, String>): Pair<Settings, Set<String>> {
        val defaults = json.encodeToJsonElement(Settings()).jsonObject
        val obj = defaults.toMutableMap()
        val set = mutableSetOf<String>()
        val ordered = PREFIXES.flatMap { p -> vars.filterKeys { it.startsWith(p) }.map { (k, v) -> k.removePrefix(p) to v } }
        for ((name, raw) in ordered) {
            val field = name.lowercase()
            val def = defaults[field] ?: continue
            set += field
            obj[field] = when {
                def is JsonPrimitive && def.isString -> JsonPrimitive(raw)
                def is JsonPrimitive && def.booleanOrNull != null -> JsonPrimitive(raw.trim().lowercase() in setOf("1", "true", "yes", "on"))
                else -> json.parseToJsonElement(raw) // numbers, lists, maps
            }
        }
        // KARLSTODIAN_DATA: the data folder (database + settings.json), unless the database path is set itself
        val dataDir = vars["KARLSTODIAN_DATA"]?.takeIf { it.isNotBlank() }
        if (dataDir != null && "db_path" !in set) obj["db_path"] = JsonPrimitive(Paths.get(dataDir).resolve("pruner.db").toString())
        return decode(JsonObject(obj)) to set
    }

    private fun dotEnv(): Map<String, String> {
        val f = Paths.get(".env")
        if (!Files.exists(f)) return emptyMap()
        return Files.readAllLines(f).mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || '=' !in t) null
            else t.substringBefore('=').trim() to t.substringAfter('=').trim().removeSurrounding("\"")
        }.toMap()
    }

    private fun normUrl(u: String): String {
        val s = u.trim().trimEnd('/')
        return try {
            val uri = URI(s)
            if (uri.authority == null) s else "${uri.scheme}://${uri.authority.lowercase()}${uri.path ?: ""}"
        } catch (_: Exception) {
            s
        }
    }
}
