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
            "POST plex/library/collections?type=1&title=Leaving Soon&smart=0&sectionId=1&uri=server://MID/com.plexapp.plugins.library/library/metadata/900",
            "POST plex/library/collections?type=2&title=Leaving Soon&smart=0&sectionId=2&uri=server://MID/com.plexapp.plugins.library/library/metadata/800",
        ), env.writes)
    }

    @Test fun collectionKeptInStep() = runBlocking {
        env.overrides["plex" to "/library/sections/1/collections"] = HttpStatusCode.OK to
            """{"MediaContainer": {"Metadata": [{"title": "Leaving Soon", "ratingKey": "55"}, {"title": "Other", "ratingKey": "56"}]}}"""
        env.overrides["plex" to "/library/collections/55/children"] = HttpStatusCode.OK to
            """{"MediaContainer": {"Metadata": [{"ratingKey": "777"}]}}"""
        env.overrides["plex" to "/library/sections/2/collections"] = HttpStatusCode.OK to
            """{"MediaContainer": {"Metadata": [{"title": "Leaving Soon", "ratingKey": "66"}]}}"""
        Removals.mark(listOf(movie().id!!))
        Removals.syncLeavingSoon()
        assertEquals(listOf(
            "PUT plex/library/collections/55/items?uri=server://MID/com.plexapp.plugins.library/library/metadata/900",
            "DELETE plex/library/collections/55/children/777",
            "DELETE plex/library/collections/66", // nothing leaving from the TV section any more
        ), env.writes)
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
}
