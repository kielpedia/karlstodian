package karlstodian

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class SuggestTest {
    private lateinit var env: TestEnv
    private val db get() = Store.db

    @BeforeTest fun setUp() {
        env = TestEnv()
        // the two root folders: the tighter one counts
        env.overrides["radarr" to "/api/v3/rootfolder"] = HttpStatusCode.OK to """[{"path": "/data/movies", "freeSpace": 999999950}]"""
        env.overrides["sonarr" to "/api/v3/rootfolder"] = HttpStatusCode.OK to """[{"path": "/data/tv", "freeSpace": 2000000000}]"""
        runBlocking { runScan() }
        settings("new_grace_days" to 0) // the test titles are new to Plex
        assertTrue(scores().values.all { it in 0.0..99.9 }, "both test titles get a score below 100")
    }
    @AfterTest fun tearDown() { env.close() }

    private fun settings(vararg kv: Pair<String, Any>) = Config.apply(Config.merge(buildJsonObject {
        for ((k, v) in kv) when (v) { is Boolean -> put(k, v); is Number -> put(k, v); else -> put(k, v.toString()) }
    }))
    private fun scores(): Map<String, Double> {
        val items = db.items()
        val s = scoreItems(items.map { inputOf(it, Config.current) }, Config.current)
        return items.associate { it.title to s.getValue(it.id!!).score!! }
    }
    private fun suggested() = db.items().filter { it.suggested_at != null }.map { it.title }.toSet()
    private fun movie() = db.items().single { it.kind == "movie" }
    private fun show() = db.items().single { it.kind == "show" }

    @Test fun freeSpaceComesFromTheScan() {
        assertEquals(999999950L, Suggestions.freeBytes()!!.first)
    }

    @Test fun scoreThresholdWithCap() {
        settings("suggest_min_score" to 0, "max_suggestions" to 1)
        assertEquals(1, Suggestions.refresh())
        val top = scores().maxBy { it.value }.key
        assertEquals(setOf(top), suggested())
        assertTrue(db.items().single { it.title == top }.suggest_reason!!.startsWith("Score "))

        settings("max_suggestions" to 10)
        assertEquals(2, Suggestions.refresh())
        settings("suggest_min_score" to 100)
        assertEquals(0, Suggestions.refresh())
        assertEquals(emptySet(), suggested())
    }

    @Test fun turnedOff() {
        settings("suggest_min_score" to 0)
        Suggestions.refresh()
        settings("auto_suggest" to false)
        assertEquals(0, Suggestions.refresh())
        assertEquals(emptySet(), suggested())
    }

    @Test fun spaceGoalAddsTopScorersUntilItsMet() {
        settings("suggest_min_score" to 100) // nothing by score alone
        assertEquals(0, Suggestions.refresh())
        // 1 GB goal, 999,999,950 bytes free: 50 short, so the top scorer alone covers it
        settings("free_space_goal_gb" to 1)
        assertEquals(1, Suggestions.refresh())
        val top = scores().maxBy { it.value }.key
        assertEquals(setOf(top), suggested())
        assertTrue("under the" in db.items().single { it.title == top }.suggest_reason!!)
        // a title already leaving counts toward the goal, so nothing more is needed
        Removals.mark(listOf(db.items().single { it.title == top }.id!!))
        assertEquals(0, Suggestions.refresh())
    }

    @Test fun protectedDismissedAndRecentlyKeptAreSkipped() {
        settings("suggest_min_score" to 0)
        val m = movie(); m.keep = true; db.saveItem(m)
        val s = show(); s.suggest_dismissed = true; db.saveItem(s)
        assertEquals(0, Suggestions.refresh())

        m.keep = false; db.saveItem(m)
        val r = Removals.mark(listOf(m.id!!)).marked.single()
        Removals.cancel(r.id!!, "Kept by the owner")
        assertEquals(0, Suggestions.refresh(), "kept during its grace period: not suggested again for a while")
    }

    @Test fun approvingMarksItAndLogsWhy() {
        settings("suggest_min_score" to 0)
        Suggestions.refresh()
        val r = Removals.mark(listOf(movie().id!!)).marked.single()
        assertNull(movie().suggested_at)
        assertTrue(db.audit(10, r.id).single().detail.startsWith("Suggested: Score "))
    }

    @Test fun api() = testApplication {
        application { module() }
        settings("suggest_min_score" to 0)
        val refreshed = json.parseToJsonElement(client.post("/api/suggestions/refresh").bodyAsText()).jsonObject
        assertEquals(2, refreshed["suggested"]!!.jsonPrimitive.int)
        assertEquals(999999950L, refreshed["free_bytes"]!!.jsonPrimitive.long)

        val item = json.parseToJsonElement(client.get("/api/items/${show().id}").bodyAsText()).jsonObject
        assertTrue(item["suggest_reason"]!!.jsonPrimitive.content.startsWith("Score "))

        // dismissing takes it off the list, and the next refresh leaves it off
        val res = client.patch("/api/items/${show().id}") {
            contentType(ContentType.Application.Json); setBody("""{"suggest_dismissed": true}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertNull(show().suggested_at)
        client.post("/api/suggestions/refresh")
        assertEquals(setOf("Movie A"), suggested())

        // saving settings recomputes them
        client.put("/api/settings") { contentType(ContentType.Application.Json); setBody("""{"auto_suggest": false}""") }
        assertEquals(emptySet(), suggested())
    }
}
