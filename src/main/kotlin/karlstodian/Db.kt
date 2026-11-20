package karlstodian

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite access. The schema is the one the Python version created, so an existing
 * data/pruner.db carries over as is (datetimes are naive-UTC text, booleans 0/1).
 */
class Db(path: String) {
    private val conn: Connection
    private val lock = ReentrantLock()

    init {
        Paths.get(path).toAbsolutePath().parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
        createTables()
        migrate()
    }

    fun close() = conn.close()

    /** Run [block] in one transaction; everything in it commits or nothing does. */
    fun <T> tx(block: Db.() -> T): T = lock.withLock {
        val outer = conn.autoCommit
        if (!outer) return block() // already inside a transaction
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    // ------------------------------------------------------------------ schema

    private fun createTables() = lock.withLock {
        conn.createStatement().use { st ->
            for (sql in SCHEMA) st.execute(sql)
        }
    }

    private fun migrate() = lock.withLock {
        for ((table, column, ddl) in MIGRATIONS) {
            val cols = query("PRAGMA table_info(\"$table\")") { it.getString("name") }
            if (cols.isNotEmpty() && column !in cols) exec("ALTER TABLE \"$table\" ADD COLUMN \"$column\" $ddl")
        }
    }

    // ------------------------------------------------------------------ generic helpers

    fun exec(sql: String, vararg args: Any?): Int = lock.withLock {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeUpdate()
        }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = lock.withLock {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<T>()
                while (rs.next()) out += map(rs)
                out
            }
        }
    }

    fun <T> all(t: Table<T>, where: String = "", vararg args: Any?): List<T> =
        query("SELECT * FROM ${t.name} $where", *args) { t.read(it) }

    /** Insert (id null) or update by primary key. Returns the row's id for tables with integer ids. */
    fun <T> save(t: Table<T>, row: T): Long? = lock.withLock {
        val values = t.write(row)
        val pk = values[t.pk]
        val upsert = t.pk != "id" // tables keyed by a natural key (torrent hash) replace in place
        if (pk == null || upsert) {
            val cols = values.keys.filter { !(it == "id" && values[it] == null) }
            val verb = if (upsert) "INSERT OR REPLACE" else "INSERT"
            conn.prepareStatement(
                "$verb INTO ${t.name} (${cols.joinToString { "\"$it\"" }}) VALUES (${cols.joinToString { "?" }})"
            ).use { ps ->
                cols.forEachIndexed { i, c -> ps.setObject(i + 1, values[c]) }
                ps.executeUpdate()
            }
            if (upsert) null else query("SELECT last_insert_rowid()") { it.getLong(1) }.first()
        } else {
            val cols = values.keys.filter { it != t.pk }
            conn.prepareStatement(
                "UPDATE ${t.name} SET ${cols.joinToString { "\"$it\" = ?" }} WHERE \"${t.pk}\" = ?"
            ).use { ps ->
                cols.forEachIndexed { i, c -> ps.setObject(i + 1, values[c]) }
                ps.setObject(cols.size + 1, pk)
                ps.executeUpdate()
            }
            pk as Long
        }
    }

    // ------------------------------------------------------------------ typed access

    fun items(): List<Item> = all(ITEMS)
    fun item(id: Long): Item? = all(ITEMS, "WHERE id = ?", id).firstOrNull()
    fun saveItem(i: Item) { i.id = save(ITEMS, i) }
    fun files(itemId: Long): List<MediaFile> = all(FILES, "WHERE item_id = ? ORDER BY path", itemId)
    fun userPlays(itemId: Long): List<UserPlay> = all(USER_PLAYS, "WHERE item_id = ? ORDER BY plays DESC", itemId)
    fun torrents(hashes: Collection<String>): List<Torrent> =
        if (hashes.isEmpty()) emptyList()
        else all(TORRENTS, "WHERE hash IN (${hashes.joinToString { "?" }})", *hashes.toTypedArray())
    fun torrent(hash: String): Torrent? = torrents(listOf(hash)).firstOrNull()
    fun scanRuns(limit: Int): List<ScanRun> = all(SCAN_RUNS, "ORDER BY id DESC LIMIT ?", limit)
    fun scanRun(id: Long): ScanRun? = all(SCAN_RUNS, "WHERE id = ?", id).firstOrNull()
    fun saveScanRun(r: ScanRun) { r.id = save(SCAN_RUNS, r) }

    fun removals(where: String = "", vararg args: Any?): List<Removal> = all(REMOVALS, where, *args)
    fun removal(id: Long): Removal? = removals("WHERE id = ?", id).firstOrNull()
    fun saveRemoval(r: Removal) { r.id = save(REMOVALS, r) }
    fun removalTorrents(removalId: Long): List<RemovalTorrent> = all(REMOVAL_TORRENTS, "WHERE removal_id = ? ORDER BY id", removalId)
    fun saveRemovalTorrent(t: RemovalTorrent) { t.id = save(REMOVAL_TORRENTS, t) }
    fun audit(limit: Int, removalId: Long? = null): List<AuditEntry> =
        if (removalId == null) all(AUDIT, "ORDER BY id DESC LIMIT ?", limit)
        else all(AUDIT, "WHERE removal_id = ? ORDER BY id DESC LIMIT ?", removalId, limit)
    fun addAudit(e: AuditEntry) { e.id = save(AUDIT, e) }

    companion object {
        val ITEMS = Table("item", Item.serializer())
        val FILES = Table("mediafile", MediaFile.serializer())
        val TORRENTS = Table("torrent", Torrent.serializer(), pk = "hash")
        val USER_PLAYS = Table("userplay", UserPlay.serializer())
        val SCAN_RUNS = Table("scanrun", ScanRun.serializer())
        val REMOVALS = Table("removal", Removal.serializer())
        val REMOVAL_TORRENTS = Table("removaltorrent", RemovalTorrent.serializer())
        val AUDIT = Table("audit", AuditEntry.serializer())

        /** Columns added after the first release: (table, column, SQL definition) */
        val MIGRATIONS = listOf(
            Triple("item", "watched_episodes_all", "INTEGER NOT NULL DEFAULT 0"),
            Triple("userplay", "episodes_watched", "INTEGER NOT NULL DEFAULT 0"),
            Triple("item", "plex_section_key", "VARCHAR"),
            Triple("removal", "watch_mark", "INTEGER NOT NULL DEFAULT 0"),
        )

        private val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS item (
                id INTEGER NOT NULL, "key" VARCHAR NOT NULL, kind VARCHAR NOT NULL, title VARCHAR NOT NULL,
                year INTEGER, tmdb_id INTEGER, tvdb_id INTEGER, imdb_id VARCHAR, arr_id INTEGER NOT NULL,
                arr_path VARCHAR, arr_tags VARCHAR NOT NULL, in_plex BOOLEAN NOT NULL, plex_rating_key VARCHAR,
                plex_added_at DATETIME, plex_view_count INTEGER NOT NULL, plex_last_viewed_at DATETIME,
                episode_count INTEGER NOT NULL, watched_episode_count INTEGER NOT NULL,
                watched_episodes_all INTEGER NOT NULL DEFAULT 0, play_count INTEGER NOT NULL, plex_section_key VARCHAR,
                unique_viewers INTEGER NOT NULL, last_played_at DATETIME, size_bytes INTEGER NOT NULL,
                low_quality_bytes INTEGER NOT NULL, min_resolution INTEGER, max_resolution INTEGER,
                keep BOOLEAN NOT NULL, quality_exempt BOOLEAN NOT NULL, last_seen_scan INTEGER,
                PRIMARY KEY (id))""",
            """CREATE UNIQUE INDEX IF NOT EXISTS ix_item_key ON item ("key")""",
            """CREATE INDEX IF NOT EXISTS ix_item_kind ON item (kind)""",
            """CREATE TABLE IF NOT EXISTS torrent (
                hash VARCHAR NOT NULL, name VARCHAR NOT NULL, size INTEGER NOT NULL, ratio FLOAT NOT NULL,
                seeding_time INTEGER NOT NULL, tracker VARCHAR, category VARCHAR, state VARCHAR,
                added_on DATETIME, content_path VARCHAR, PRIMARY KEY (hash))""",
            """CREATE TABLE IF NOT EXISTS scanrun (
                id INTEGER NOT NULL, started_at DATETIME NOT NULL, finished_at DATETIME,
                status VARCHAR NOT NULL, summary VARCHAR NOT NULL, PRIMARY KEY (id))""",
            """CREATE TABLE IF NOT EXISTS mediafile (
                id INTEGER NOT NULL, item_id INTEGER NOT NULL, arr_file_id INTEGER NOT NULL, path VARCHAR NOT NULL,
                local_path VARCHAR, size INTEGER NOT NULL, resolution INTEGER, quality_name VARCHAR,
                video_codec VARCHAR, torrent_hash VARCHAR, device INTEGER, inode INTEGER, nlink INTEGER,
                PRIMARY KEY (id), FOREIGN KEY(item_id) REFERENCES item (id))""",
            """CREATE INDEX IF NOT EXISTS ix_mediafile_item_id ON mediafile (item_id)""",
            """CREATE INDEX IF NOT EXISTS ix_mediafile_torrent_hash ON mediafile (torrent_hash)""",
            """CREATE TABLE IF NOT EXISTS userplay (
                id INTEGER NOT NULL, item_id INTEGER NOT NULL, user VARCHAR NOT NULL, plays INTEGER NOT NULL,
                last_played_at DATETIME, episodes_watched INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (id), FOREIGN KEY(item_id) REFERENCES item (id))""",
            """CREATE INDEX IF NOT EXISTS ix_userplay_item_id ON userplay (item_id)""",
            """CREATE TABLE IF NOT EXISTS removal (
                id INTEGER NOT NULL, item_key VARCHAR NOT NULL, item_id INTEGER, kind VARCHAR NOT NULL,
                title VARCHAR NOT NULL, year INTEGER, size_bytes INTEGER NOT NULL, state VARCHAR NOT NULL,
                marked_at DATETIME NOT NULL, remove_after DATETIME NOT NULL, finished_at DATETIME,
                error VARCHAR, dry_run BOOLEAN NOT NULL, watch_mark INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (id))""",
            """CREATE INDEX IF NOT EXISTS ix_removal_state ON removal (state)""",
            """CREATE INDEX IF NOT EXISTS ix_removal_item_key ON removal (item_key)""",
            """CREATE TABLE IF NOT EXISTS removaltorrent (
                id INTEGER NOT NULL, removal_id INTEGER NOT NULL, hash VARCHAR NOT NULL, name VARCHAR NOT NULL,
                tracker_host VARCHAR, size INTEGER NOT NULL, state VARCHAR NOT NULL, seed_until DATETIME,
                PRIMARY KEY (id), FOREIGN KEY(removal_id) REFERENCES removal (id))""",
            """CREATE INDEX IF NOT EXISTS ix_removaltorrent_removal_id ON removaltorrent (removal_id)""",
            """CREATE TABLE IF NOT EXISTS audit (
                id INTEGER NOT NULL, at DATETIME NOT NULL, removal_id INTEGER, title VARCHAR, action VARCHAR NOT NULL,
                detail VARCHAR NOT NULL, dry_run BOOLEAN NOT NULL, PRIMARY KEY (id))""",
        )
    }
}

/**
 * Maps a @Serializable row class to its table, column per property. Booleans are stored as
 * 0/1 and Instants as naive UTC text ("2026-10-24 09:00:53.749229"), like SQLAlchemy did.
 */
class Table<T>(val name: String, private val ser: KSerializer<T>, val pk: String = "id") {
    private val cols = (0 until ser.descriptor.elementsCount).map { i ->
        val d = ser.descriptor.getElementDescriptor(i)
        Col(ser.descriptor.getElementName(i), d.serialName.removeSuffix("?") == "Instant", d.kind)
    }

    private data class Col(val name: String, val instant: Boolean, val kind: Any)

    fun write(row: T): LinkedHashMap<String, Any?> {
        val o = json.encodeToJsonElement(ser, row).jsonObject
        val out = LinkedHashMap<String, Any?>()
        for (c in cols) {
            val v = o[c.name] as? JsonPrimitive
            out[c.name] = when {
                v == null || v is JsonNull -> null
                c.instant -> toDb(Instant.parse(v.content))
                c.kind == PrimitiveKind.BOOLEAN -> if (v.boolean) 1 else 0
                c.kind == PrimitiveKind.STRING -> v.content
                c.kind == PrimitiveKind.DOUBLE || c.kind == PrimitiveKind.FLOAT -> v.double
                else -> v.long
            }
        }
        return out
    }

    fun read(rs: ResultSet): T {
        val o = buildJsonObject {
            for (c in cols) {
                val raw = rs.getObject(c.name)
                put(c.name, when {
                    raw == null -> JsonNull
                    c.instant -> JsonPrimitive(fromDb(raw.toString()).toString())
                    c.kind == PrimitiveKind.BOOLEAN -> JsonPrimitive((raw as Number).toInt() != 0)
                    c.kind == PrimitiveKind.STRING -> JsonPrimitive(raw.toString())
                    raw is Number -> JsonPrimitive(raw)
                    else -> JsonPrimitive(raw.toString())
                })
            }
        }
        return json.decodeFromJsonElement(ser, o)
    }

    companion object {
        private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
        fun toDb(i: Instant): String = LocalDateTime.ofInstant(i, ZoneOffset.UTC).format(FMT)
        fun fromDb(s: String): Instant {
            val t = s.trim().replace(' ', 'T')
            return if (t.endsWith("Z") || Regex("[+-]\\d\\d:\\d\\d$").containsMatchIn(t)) Instant.parse(t)
            else LocalDateTime.parse(t).toInstant(ZoneOffset.UTC)
        }
    }
}
