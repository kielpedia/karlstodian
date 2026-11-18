package karlstodian

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class ScanTest {
    private lateinit var env: TestEnv

    @BeforeTest fun setUp() { env = TestEnv() }
    @AfterTest fun tearDown() { env.close() }

    private fun scan() = runBlocking { runScan() }
    private fun JsonElement?.o() = this!!.jsonObject

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module() }
        block()
    }

    private suspend fun HttpResponse.obj() = json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpClient.putJson(url: String, body: String) =
        put(url) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpClient.postJson(url: String, body: String) =
        post(url) { contentType(ContentType.Application.Json); setBody(body) }

    @Test fun fullScan() {
        val result = scan()
        assertEquals(JsonObject(emptyMap()), result["warnings"], result.toString())
        val c = result["counts"].o()
        assertEquals(1, c.int("movies")); assertEquals(1, c.int("shows")); assertEquals(2, c.int("files_with_torrent"))
        if (env.posix) assertEquals(2, c.int("files_with_inode"))

        val db = Store.db
        val movie = db.items().single { it.kind == "movie" }
        assertTrue(movie.in_plex); assertEquals(2, movie.plex_view_count)
        assertEquals(1, movie.play_count); assertEquals(2, movie.unique_viewers) // guest started but didn't finish
        assertEquals(100, movie.low_quality_bytes); assertTrue("prune-quality-ok" in movie.arr_tags)
        val f = db.files(movie.id!!).single()
        assertEquals("abc123", f.torrent_hash)
        if (env.posix) assertEquals(2, f.nlink)
        val show = db.items().single { it.kind == "show" }
        assertEquals(10, show.episode_count); assertEquals(3, show.watched_episode_count); assertEquals(1, show.play_count)
        assertEquals(1, show.watched_episodes_all); assertEquals(0, show.low_quality_bytes)
        assertEquals(2, db.userPlays(movie.id!!).size)
        assertEquals(86400, db.torrent("abc123")!!.seeding_time)

        // user flags survive a rescan
        movie.keep = true
        db.saveItem(movie)
        scan()
        assertTrue(Store.db.items().single { it.kind == "movie" }.keep)
    }

    @Test fun failedServiceKeepsCache() {
        scan()
        env.radarrOk = false
        val result = scan()
        assertTrue("radarr" in result["warnings"].o())
        assertEquals("Movie A", Store.db.items().single { it.kind == "movie" }.title)
    }

    @Test fun api() = withApp {
        scan()
        val items = client.get("/api/items?sort=size").obj()
        assertEquals(2, items.int("total"))
        val movie = items["items"]!!.jsonArray.map { it.jsonObject }.first { it.str("kind") == "movie" }
        assertEquals(true, movie["quality_exempt_effective"]!!.jsonPrimitive.boolean)
        val id = movie.long("id")
        val detail = client.get("/api/items/$id").obj()
        assertEquals(1.5, detail["torrents"]!!.jsonArray[0].jsonObject.double("ratio"))
        assertEquals(setOf("size", "age", "watch", "quality"), movie["factors"].o().keys)
        val patched = client.patch("/api/items/$id") { contentType(ContentType.Application.Json); setBody("""{"keep": true}""") }.obj()
        assertEquals(true, patched["keep_effective"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, patched["score"])
        assertEquals("Protected", patched.str("excluded"))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/items/9999").status)
    }

    // ------------------------------------------------------------ settings page / API

    @Test fun settingsApi() = withApp {
        val v = client.get("/api/settings").obj()["values"].o()
        assertEquals(buildJsonObject { put("set", true) }, v["radarr_api_key"]) // secret never sent to the browser

        // change non-secret settings; blank secret keeps the saved key
        var r = client.putJson("/api/settings", """{"keep_tag": "keepme", "radarr_api_key": "", "scan_cron": "30 3 * * *"}""")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("keepme", Config.current.keep_tag); assertEquals("k", Config.current.radarr_api_key)
        assertNotNull(r.obj().str("next_scan"))
        val file = Config.settingsFile()
        assertEquals("keepme", json.parseToJsonElement(Files.readString(file)).jsonObject.str("keep_tag"))
        if (env.posix) assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))

        // changing a URL without re-entering its key is refused
        r = client.putJson("/api/settings", """{"radarr_url": "http://evil:7878"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status); assertTrue("re-enter" in r.obj().str("detail")!!)
        // with the key it's fine; bare host:port gets http:// added
        r = client.putJson("/api/settings", """{"radarr_url": "192.168.1.114:7878", "radarr_api_key": "new"}""")
        assertEquals(HttpStatusCode.OK, r.status); assertEquals("http://192.168.1.114:7878", Config.current.radarr_url)

        // bad cron and bad types rejected without changing anything
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/settings", """{"scan_cron": "nope"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/settings", """{"low_quality_max_resolution": "abc"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/settings", """{"bogus": 1}""").status)
        assertEquals("30 3 * * *", Config.current.scan_cron)

        // clearing a secret
        client.putJson("/api/settings", """{"_clear": ["qbit_password"]}""")
        assertEquals("", Config.current.qbit_password)

        // page is served
        assertTrue("Karlstodian" in client.get("/settings").bodyAsText())
    }

    @Test fun settingsSurviveRestart() {
        Config.apply(Config.merge(buildJsonObject { put("plex_url", "http://10.0.0.5:32400"); put("plex_token", "tok") }))
        Config.reload(mapOf("PRUNER_DB_PATH" to env.dir.resolve("t.db").toString()))
        assertEquals("http://10.0.0.5:32400", Config.current.plex_url); assertEquals("tok", Config.current.plex_token)
    }

    @Test fun connectionTestUsesFormValues() = withApp {
        env.overrides["radarr" to "/api/v3/system/status"] = HttpStatusCode.OK to """{"appName": "Radarr", "version": "5.9"}"""
        env.overrides["sonarr2" to "/api/v3/system/status"] = HttpStatusCode.Unauthorized to "{}"
        env.overrides["notradarr" to "/api/v3/system/status"] = HttpStatusCode.OK to """{"appName": "Sonarr", "version": "4"}"""

        var r = client.postJson("/api/settings/test/radarr", "{}").obj() // saved values
        assertTrue(r["ok"]!!.jsonPrimitive.boolean); assertEquals("5.9", r["info"].o().str("version"))
        r = client.postJson("/api/settings/test/sonarr", """{"sonarr_url": "http://sonarr2", "sonarr_api_key": "x"}""").obj()
        assertFalse(r["ok"]!!.jsonPrimitive.boolean); assertTrue("wrong key" in r.str("error")!!)
        r = client.postJson("/api/settings/test/radarr", """{"radarr_url": "http://notradarr", "radarr_api_key": "x"}""").obj()
        assertFalse(r["ok"]!!.jsonPrimitive.boolean); assertTrue("Sonarr" in r.str("error")!!)
        r = client.postJson("/api/settings/test/radarr", """{"radarr_url": "http://elsewhere"}""").obj()
        assertFalse(r["ok"]!!.jsonPrimitive.boolean); assertTrue("re-enter" in r.str("error")!!) // saved key not sent to a new URL
    }

    // ------------------------------------------------------------ episodes + scoring

    @Test fun showEpisodeShareCountsAnyUser() {
        val rows = json.parseToJsonElement("""[
            {"media_type": "episode", "rating_key": 1, "grandparent_rating_key": 800, "parent_media_index": 1, "media_index": 1, "friendly_name": "A", "watched_status": 1, "stopped": 1},
            {"media_type": "episode", "rating_key": 1, "grandparent_rating_key": 800, "parent_media_index": 1, "media_index": 1, "friendly_name": "B", "watched_status": 1, "stopped": 2},
            {"media_type": "episode", "rating_key": 99, "grandparent_rating_key": 800, "parent_media_index": 1, "media_index": 2, "friendly_name": "B", "watched_status": 1, "stopped": 3},
            {"media_type": "episode", "rating_key": 3, "grandparent_rating_key": 800, "parent_media_index": 1, "media_index": 3, "friendly_name": "A", "watched_status": 0, "stopped": 4}
        ]""").jsonArray.map { it.jsonObject }
        val (totals, perUser) = aggregatePlays(rows)
        assertEquals(2, totals["800"]!!.eps.size) // S1E1 (twice) + S1E2; unfinished S1E3 doesn't count
        assertEquals(3, totals["800"]!!.plays)
        assertEquals(1, perUser["800" to "A"]!!.eps.size); assertEquals(2, perUser["800" to "B"]!!.eps.size)
    }

    private val now = Instant.now()
    private fun daysAgo(d: Long) = now.minus(Duration.ofDays(d))

    private fun item(id: Long, keep: Boolean = false, exempt: Boolean = false, fill: Item.() -> Unit = {}): ScoreInput {
        val i = Item(id = id, key = "k$id", kind = "movie", title = "t$id", arr_id = id, in_plex = true,
            size_bytes = 10_000_000_000, plex_added_at = daysAgo(400))
        i.fill()
        return ScoreInput(i, keep, exempt)
    }

    private fun cfg(weights: Map<String, Double> = mapOf("size" to 30.0, "age" to 20.0, "watch" to 40.0, "quality" to 10.0)) =
        Settings(score_weights = weights).validated()

    @Test fun scoringFactorsAndExclusions() {
        val items = listOf(
            item(1) { size_bytes = 300_000_000_000; kind = "show"; episode_count = 100; watched_episodes_all = 10 }, // big, 10% watched
            item(2) { size_bytes = 300_000_000_000; kind = "show"; episode_count = 100; watched_episodes_all = 90; last_played_at = daysAgo(3) },
            item(3) { size_bytes = 5_000_000_000; play_count = 3; last_played_at = daysAgo(10) },  // small, well watched
            item(4) { size_bytes = 50_000_000_000; low_quality_bytes = 50_000_000_000 },          // all low quality
            item(5, exempt = true) { size_bytes = 50_000_000_000; low_quality_bytes = 50_000_000_000 }, // ...but exempt
            item(6, keep = true),
            item(7) { in_plex = false; plex_added_at = null },
            item(8) { plex_added_at = daysAgo(5) },
        )
        val r = scoreItems(items, cfg())
        // episode share drives the watch factor for shows
        assertTrue(r[1]!!.factors["watch"]!!.score > r[2]!!.factors["watch"]!!.score)
        assertTrue("10 of 100 episodes watched (10%)" in r[1]!!.factors["watch"]!!.note)
        assertTrue(r[1]!!.score!! > r[2]!!.score!!); assertTrue(r[2]!!.score!! > r[3]!!.score!!)
        // never watched + never played = maximum watch factor
        assertEquals(100.0, r[4]!!.factors["watch"]!!.score)
        // quality
        assertEquals(100.0, r[4]!!.factors["quality"]!!.score); assertEquals(0.0, r[5]!!.factors["quality"]!!.score)
        assertTrue(r[4]!!.score!! > r[5]!!.score!!)
        // exclusions
        assertNull(r[6]!!.score); assertEquals("Protected", r[6]!!.excluded)
        assertNull(r[7]!!.score); assertEquals("Not in Plex", r[7]!!.excluded)
        assertNull(r[8]!!.score); assertTrue(r[8]!!.excluded!!.startsWith("Added"))
        // weights: only quality counts -> score equals the quality factor
        val q = scoreItems(items, cfg(mapOf("size" to 0.0, "age" to 0.0, "watch" to 0.0, "quality" to 50.0)))
        assertEquals(100.0, q[4]!!.score); assertEquals(0.0, q[1]!!.score)
    }

    @Test fun plexWatchedCountUsedWhenHigher() {
        // Episodes watched before Tautulli existed still count, via Plex's own count
        assertEquals(0.8, watchedShare(item(1) { kind = "show"; episode_count = 10; watched_episodes_all = 2; watched_episode_count = 8 }.item))
        assertEquals(1.0, watchedShare(item(1) { kind = "show"; episode_count = 10; watched_episodes_all = 15 }.item))
    }

    @Test fun weightsValidatedAndSaved() = withApp {
        assertEquals(HttpStatusCode.OK, client.putJson("/api/settings", """{"score_weights": {"size": 10, "age": 10, "watch": 70, "quality": 10}}""").status)
        assertEquals(70.0, Config.current.score_weights["watch"])
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/settings", """{"score_weights": {"bogus": 5}}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/settings", """{"score_weights": {"size": 500}}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/settings", """{"age_cap_days": 0}""").status)
    }

    @Test fun migrationAddsNewColumns() {
        // A database from before scoring gets the new columns on startup
        val db = env.dir.resolve("old.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, key VARCHAR, kind VARCHAR, title VARCHAR, arr_id INTEGER)")
                it.execute("CREATE TABLE userplay (id INTEGER PRIMARY KEY, item_id INTEGER, user VARCHAR, plays INTEGER)")
            }
        }
        Db(db).close()
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            fun cols(t: String) = c.createStatement().executeQuery("PRAGMA table_info($t)").let { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
            assertTrue("watched_episodes_all" in cols("item"))
            assertTrue("episodes_watched" in cols("userplay"))
        }
    }

    @Test fun readsPythonEraDatetimes() {
        assertEquals(Instant.parse("2026-10-24T09:00:53.749229Z"), Table.fromDb("2026-10-24 09:00:53.749229"))
        assertEquals("2026-10-24 09:00:53.749229", Table.toDb(Instant.parse("2026-10-24T09:00:53.749229Z")))
    }
}
