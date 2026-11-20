package karlstodian

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class RemovalTest {
    private lateinit var env: TestEnv

    @BeforeTest fun setUp() {
        env = TestEnv()
        env.overrides["plex" to "/identity"] = HttpStatusCode.OK to """{"MediaContainer": {"machineIdentifier": "MID"}}"""
        env.overrides["plex" to "/library/sections/1/collections"] = HttpStatusCode.OK to """{"MediaContainer": {}}"""
        env.overrides["plex" to "/library/sections/2/collections"] = HttpStatusCode.OK to """{"MediaContainer": {}}"""
        env.overrides["plex" to "/library/sections/1/refresh"] = HttpStatusCode.OK to ""
        env.overrides["radarr" to "/api/v3/movie/10"] = HttpStatusCode.OK to """{"id": 10}"""
        env.overrides["radarr" to "/api/v3/moviefile"] = HttpStatusCode.OK to """[{"id": 1}]"""
        env.overrides["sonarr" to "/api/v3/series/20"] = HttpStatusCode.OK to """{"id": 20}"""
        runBlocking { runScan() }
        env.writes.clear()
    }
    @AfterTest fun tearDown() { env.close() }

    private val db get() = Store.db
    private fun movie() = db.items().single { it.kind == "movie" }
    private fun show() = db.items().single { it.kind == "show" }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module() }
        block()
    }
    private suspend fun HttpResponse.obj() = json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpClient.send(method: HttpMethod, url: String, body: String? = null) = request(url) {
        this.method = method
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    @Test fun markListsTitlesForTheGracePeriod() {
        val now = Instant.parse("2026-11-19T12:00:00Z")
        val res = Removals.mark(listOf(movie().id!!), now = now)
        val r = res.marked.single()
        assertEquals("leaving", r.state)
        assertEquals(now.plus(Duration.ofDays(14)), r.remove_after)
        assertEquals("Movie A", r.title); assertEquals("movie:10", r.item_key); assertEquals(100, r.size_bytes)
        assertEquals("marked", db.audit(10, r.id).single().action)
        // a second mark is refused
        assertEquals(mapOf(movie().id!! to "already leaving"), Removals.mark(listOf(movie().id!!)).refused)
    }

    @Test fun protectedAndUnknownTitlesAreRefused() {
        val s = show(); s.keep = true; db.saveItem(s)
        val res = Removals.mark(listOf(s.id!!, 9999))
        assertTrue(res.marked.isEmpty())
        assertEquals(mapOf(s.id!! to "protected", 9999L to "not found"), res.refused)
        val m = movie(); m.in_plex = false; db.saveItem(m)
        assertEquals("not in Plex", Removals.mark(listOf(m.id!!)).refused[m.id!!])
    }

    @Test fun collectionCreatedPerSection() = runBlocking {
        Removals.mark(listOf(movie().id!!, show().id!!))
        Removals.syncLeavingSoon()
        assertEquals(listOf(
            "POST plex/library/collections?type=1&title=Leaving Soon: Movies&smart=0&sectionId=1&uri=server://MID/com.plexapp.plugins.library/library/metadata/900",
            "POST plex/library/collections?type=2&title=Leaving Soon: TV&smart=0&sectionId=2&uri=server://MID/com.plexapp.plugins.library/library/metadata/800",
        ), env.writes)
    }

    @Test fun collectionKeptInStep() = runBlocking {
        env.overrides["plex" to "/library/sections/1/collections"] = HttpStatusCode.OK to
            """{"MediaContainer": {"Metadata": [{"title": "Leaving Soon", "ratingKey": "55"}, {"title": "Other", "ratingKey": "56"}]}}"""
        env.overrides["plex" to "/library/collections/55/children"] = HttpStatusCode.OK to
            """{"MediaContainer": {"Metadata": [{"ratingKey": "777"}]}}"""
        env.overrides["plex" to "/library/sections/2/collections"] = HttpStatusCode.OK to
            """{"MediaContainer": {"Metadata": [{"title": "Leaving Soon", "ratingKey": "66"}]}}"""
        env.overrides["plex" to "/hubs/sections/1/manage"] = HttpStatusCode.OK to """{"MediaContainer": {"size": 0}}"""
        Removals.mark(listOf(movie().id!!))
        Removals.syncLeavingSoon()
        assertEquals(listOf(
            "PUT plex/library/sections/1/all?type=18&id=55&title.value=Leaving Soon: Movies&title.locked=1", // old name
            "PUT plex/library/collections/55/items?uri=server://MID/com.plexapp.plugins.library/library/metadata/900",
            "DELETE plex/library/collections/55/children/777",
            "POST plex/hubs/sections/1/manage?metadataItemId=55&promotedToRecommended=1&promotedToOwnHome=1&promotedToSharedHome=1",
            "DELETE plex/library/collections/66", // nothing leaving from the TV section any more
        ), env.writes)
    }

    @Test fun pinnedCollectionIsLeftAlone() = runBlocking {
        env.overrides["plex" to "/library/sections/1/collections"] = HttpStatusCode.OK to
            """{"MediaContainer": {"Metadata": [{"title": "Leaving Soon: Movies", "ratingKey": "55"}]}}"""
        env.overrides["plex" to "/library/collections/55/children"] = HttpStatusCode.OK to
            """{"MediaContainer": {"Metadata": [{"ratingKey": "900"}]}}"""
        env.overrides["plex" to "/hubs/sections/1/manage"] = HttpStatusCode.OK to
            """{"MediaContainer": {"size": 1, "Hub": [{"identifier": "custom.collection.1.55"}]}}"""
        Removals.mark(listOf(movie().id!!))
        Removals.syncSafely()
        assertEquals(emptyList(), env.writes)
        assertNull(Removals.plexSyncError)
    }

    @Test fun pinFailureIsNotASyncError() = runBlocking {
        env.overrides["plex" to "/library/sections/1/collections"] = HttpStatusCode.OK to
            """{"MediaContainer": {"Metadata": [{"title": "Leaving Soon: Movies", "ratingKey": "55"}]}}"""
        env.overrides["plex" to "/library/collections/55/children"] = HttpStatusCode.OK to """{"MediaContainer": {}}"""
        env.overrides["plex" to "/hubs/sections/1/manage"] = HttpStatusCode.InternalServerError to "{}"
        Removals.mark(listOf(movie().id!!))
        Removals.syncSafely()
        assertNull(Removals.plexSyncError) // the collection itself was still updated
        assertEquals(listOf("PUT plex/library/collections/55/items?uri=server://MID/com.plexapp.plugins.library/library/metadata/900"), env.writes)
    }

    @Test fun plexFailureDoesNotBlockMarking() = runBlocking {
        env.overrides["plex" to "/identity"] = HttpStatusCode.InternalServerError to "{}"
        Removals.mark(listOf(movie().id!!))
        Removals.syncSafely()
        assertNotNull(Removals.plexSyncError)
        assertEquals(1, Removals.active().size)
        env.overrides["plex" to "/identity"] = HttpStatusCode.OK to """{"MediaContainer": {"machineIdentifier": "MID"}}"""
        Removals.syncSafely()
        assertNull(Removals.plexSyncError)
    }

    @Test fun api() = withApp {
        val id = movie().id!!
        var r = client.send(HttpMethod.Post, "/api/removals", """{"item_ids": [$id, ${show().id}]}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(2, r.obj()["marked"]!!.jsonArray.size)

        val items = client.get("/api/items?limit=10").obj()["items"]!!.jsonArray.map { it.jsonObject }
        val m = items.first { it.long("id") == id }
        assertEquals("leaving", m["removal"]!!.jsonObject.str("state"))

        val list = client.get("/api/removals").obj()
        assertTrue(list["dry_run"]!!.jsonPrimitive.boolean)
        val rid = list["removals"]!!.jsonArray.map { it.jsonObject }.first { it.long("item_id") == id }.long("id")!!

        // keep it after all
        r = client.send(HttpMethod.Delete, "/api/removals/$rid")
        assertEquals("cancelled", r.obj().str("state"))
        assertEquals(HttpStatusCode.Conflict, client.send(HttpMethod.Delete, "/api/removals/$rid").status)
        assertEquals(JsonNull, client.get("/api/items/$id").obj()["removal"])

        // protecting a leaving title takes it off the list
        val showId = show().id!!
        client.send(HttpMethod.Patch, "/api/items/$showId", """{"keep": true}""")
        assertTrue(Removals.active().isEmpty())
        assertEquals("cancelled", db.removals("WHERE item_id = ?", showId).single().state)

        assertEquals(HttpStatusCode.UnprocessableEntity, client.send(HttpMethod.Post, "/api/removals", """{"item_ids": "x"}""").status)
        assertEquals(2, client.get("/api/removals?state=history").obj()["removals"]!!.jsonArray.size)
    }

    // ------------------------------------------------------------------ engine

    private val t0 = Instant.parse("2026-11-01T12:00:00Z")
    private fun days(d: Long) = t0.plus(Duration.ofDays(d))
    private fun settings(vararg kv: Pair<String, Any>) = Config.apply(Config.merge(buildJsonObject {
        for ((k, v) in kv) when (v) { is Boolean -> put(k, v); is Number -> put(k, v); else -> put(k, v.toString()) }
    }), persist = false)
    private fun removalOf(itemId: Long) = db.removals("WHERE item_id = ? ORDER BY id DESC", itemId).first()
    private fun auditText(r: Removal) = db.audit(100, r.id).reversed().joinToString("\n") { "${it.action}: ${it.detail}" }
    private fun seeded(days: Long) {
        env.overrides["qbit" to "/api/v2/torrents/info"] = HttpStatusCode.OK to
            """[{"hash": "abc123", "name": "movie.a", "size": 100, "seeding_time": ${days * 86400}, "tracker": "https://t/pk/announce"}]"""
    }

    @Test fun dryRunOnlyLogs() = runBlocking {
        Removals.mark(listOf(movie().id!!), now = t0)
        Removals.processDue(now = days(13))
        assertEquals("leaving", removalOf(movie().id!!).state) // grace period not over yet
        Removals.processDue(now = days(15))
        val r = removalOf(movie().id!!)
        assertEquals("done", r.state); assertTrue(r.dry_run)
        assertEquals(emptyList(), env.writes)
        val log = auditText(r)
        assertTrue("Would unmonitor it in Radarr and delete 1 file" in log, log)
        assertTrue("Would keep movie.a seeding until" in log, log) // seeded 1 day of 14
        assertTrue(db.audit(100, r.id).filter { it.action != "marked" }.all { it.dry_run })
        assertTrue(db.removalTorrents(r.id!!).isEmpty()) // nothing recorded as deleted in a dry run
    }

    @Test fun realRemovalWaitsForSeedTime() = runBlocking {
        settings("dry_run" to false)
        Removals.mark(listOf(movie().id!!), now = t0)
        Removals.processDue(now = days(15))
        var r = removalOf(movie().id!!)
        assertEquals("waiting_seed", r.state)
        assertEquals(listOf(
            """PUT radarr/api/v3/movie/editor {"movieIds":[10],"monitored":false}""",
            """DELETE radarr/api/v3/moviefile/bulk {"movieFileIds":[1]}""",
        ), env.writes)
        val rt = db.removalTorrents(r.id!!).single()
        assertEquals("pending", rt.state); assertEquals("t", rt.tracker_host)
        assertEquals(days(15).plus(Duration.ofDays(13)), rt.seed_until) // seeded 1 of 14 days

        env.writes.clear()
        Removals.processDue(now = days(27))
        assertEquals(emptyList(), env.writes)
        seeded(20)
        Removals.processDue(now = days(29))
        assertEquals(listOf("POST qbit/api/v2/torrents/delete hashes=abc123&deleteFiles=true"), env.writes)
        r = removalOf(movie().id!!)
        assertEquals("done", r.state); assertFalse(r.dry_run)
        assertEquals("deleted", db.removalTorrents(r.id!!).single().state)
    }

    @Test fun perTrackerSeedRule() = runBlocking {
        settings("dry_run" to false, "seed_min_days" to 30)
        seeded(10)
        Config.apply(Config.merge(buildJsonObject { putJsonObject("seed_rules") { put("t", 5) } }), persist = false)
        Removals.mark(listOf(movie().id!!), now = t0)
        Removals.processDue(now = days(15))
        assertTrue(env.writes.any { it.startsWith("POST qbit/api/v2/torrents/delete") }) // 10 days >= 5 for tracker "t"
        assertEquals("done", removalOf(movie().id!!).state)
    }

    @Test fun sharedTorrentStaysForTheOtherTitle() = runBlocking {
        settings("dry_run" to false)
        seeded(30)
        db.exec("UPDATE mediafile SET torrent_hash = 'abc123' WHERE item_id = ?", show().id)
        Removals.mark(listOf(movie().id!!), now = t0)
        Removals.processDue(now = days(15))
        val r = removalOf(movie().id!!)
        assertEquals("done", r.state)
        assertEquals("kept_shared", db.removalTorrents(r.id!!).single().state)
        assertTrue(env.writes.none { it.contains("torrents/delete") })
        assertTrue("also used by Show B" in auditText(r))
    }

    @Test fun watchingDuringGraceKeepsIt() = runBlocking {
        Removals.mark(listOf(movie().id!!), now = t0)
        val m = movie(); m.play_count += 1; db.saveItem(m)
        Removals.processDue(now = days(15))
        val r = removalOf(m.id!!)
        assertEquals("cancelled", r.state)
        assertTrue("Watched during the grace period" in auditText(r))
        assertEquals(emptyList(), env.writes)
    }

    @Test fun watchingIgnoredWhenTurnedOff() = runBlocking {
        settings("cancel_on_watch" to false)
        Removals.mark(listOf(movie().id!!), now = t0)
        val m = movie(); m.play_count += 1; db.saveItem(m)
        Removals.processDue(now = days(15))
        assertEquals("done", removalOf(m.id!!).state)
    }

    @Test fun protectedLaterIsNotRemoved() = runBlocking {
        Removals.mark(listOf(movie().id!!), now = t0)
        val m = movie(); m.keep = true; db.saveItem(m)
        Removals.processDue(now = days(15))
        assertEquals("cancelled", removalOf(m.id!!).state)
    }

    @Test fun capPerRun() = runBlocking {
        settings("max_removals_per_run" to 1)
        Removals.mark(listOf(movie().id!!, show().id!!), now = t0)
        assertEquals(1, Removals.processDue(now = days(15)).int("removed"))
        assertEquals(1, Removals.active().size)
        assertEquals(1, Removals.processDue(now = days(15)).int("removed"))
        assertTrue(Removals.active().isEmpty())
    }

    @Test fun failureThenRetry() = runBlocking {
        settings("dry_run" to false)
        seeded(30)
        env.overrides["radarr" to "/api/v3/movie/editor"] = HttpStatusCode.InternalServerError to "{}"
        val id = Removals.mark(listOf(movie().id!!), now = t0).marked.single().id!!
        var r = Removals.removeNow(id, now = days(1))
        assertEquals("failed", r.state); assertNotNull(r.error)
        assertTrue(env.writes.none { it.contains("torrents/delete") }) // stopped before the torrents
        env.overrides.remove("radarr" to "/api/v3/movie/editor")
        r = Removals.removeNow(id, now = days(1))
        assertEquals("done", r.state)
        assertNull(r.error)
        assertTrue(env.writes.any { it.contains("torrents/delete") })
    }

    @Test fun removeNowApi() = withApp {
        val id = Removals.mark(listOf(movie().id!!)).marked.single().id!!
        var r = client.send(HttpMethod.Post, "/api/removals/$id/remove")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("done", r.obj().str("state")) // dry run
        assertEquals(HttpStatusCode.Conflict, client.send(HttpMethod.Post, "/api/removals/$id/remove").status)
        val audit = json.parseToJsonElement(client.get("/api/audit?removal_id=$id").bodyAsText()).jsonArray
        assertTrue(audit.size >= 3)
        r = client.send(HttpMethod.Post, "/api/removals/run")
        assertEquals(0, r.obj().int("removed"))
        assertEquals(HttpStatusCode.OK, client.get("/removals").status)
    }

    // ------------------------------------------------------------------ not in Plex

    @Test fun notInPlexSkipsLeavingSoon() {
        val m = movie(); m.in_plex = false; db.saveItem(m)
        assertEquals("not in Plex", Removals.mark(listOf(m.id!!)).refused[m.id!!])
        val res = Removals.mark(listOf(m.id!!, show().id!!), now = t0, immediate = true)
        assertEquals(mapOf(show().id!! to "in Plex, so it goes through Leaving Soon"), res.refused)
        assertEquals(t0, res.marked.single().remove_after) // due right away, no grace period
    }

    @Test fun removeNotInPlexApi() = withApp {
        settings("dry_run" to false)
        seeded(30)
        val m = movie(); m.in_plex = false; db.saveItem(m)
        val r = client.send(HttpMethod.Post, "/api/removals", """{"item_ids": [${m.id}, ${show().id}], "now": true}""").obj()
        assertEquals("done", r["marked"]!!.jsonArray.single().jsonObject.str("state"))
        assertEquals(setOf(show().id.toString()), r["refused"]!!.jsonObject.keys)
        assertEquals(listOf(
            """PUT radarr/api/v3/movie/editor {"movieIds":[10],"monitored":false}""",
            """DELETE radarr/api/v3/moviefile/bulk {"movieFileIds":[1]}""",
            "POST qbit/api/v2/torrents/delete hashes=abc123&deleteFiles=true",
        ), env.writes) // and nothing to Plex
    }
}
