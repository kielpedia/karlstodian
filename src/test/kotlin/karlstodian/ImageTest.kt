package karlstodian

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ImageTest {
    private lateinit var env: TestEnv
    private val db get() = Store.db

    @BeforeTest fun setUp() {
        env = TestEnv()
        runBlocking { runScan() }
    }
    @AfterTest fun tearDown() { env.close() }

    private fun movie() = db.items().single { it.kind == "movie" }
    private fun show() = db.items().single { it.kind == "show" }

    @Test fun posterFromPlexIsCached() = runBlocking {
        val m = movie(); m.plex_thumb = "/library/metadata/100/thumb/1700000000"; db.saveItem(m)
        env.overrides["plex" to "/photo/:/transcode"] = HttpStatusCode.OK to "plex-poster"
        val img = Images.get(movie(), Images.Kind.POSTER)!!
        assertEquals("plex-poster", String(img.bytes))
        assertEquals("image/jpeg", img.contentType)

        // served from the disk cache: Plex isn't asked again
        env.overrides["plex" to "/photo/:/transcode"] = HttpStatusCode.InternalServerError to "down"
        assertEquals("plex-poster", String(Images.get(movie(), Images.Kind.POSTER)!!.bytes))
        assertTrue(Images.cacheDir().toFile().listFiles()!!.any { it.name.endsWith(".img") })

        // a new poster in Plex has a new path, so it's fetched again
        m.plex_thumb = "/library/metadata/100/thumb/1800000000"; db.saveItem(m)
        env.overrides["plex" to "/photo/:/transcode"] = HttpStatusCode.OK to "new-poster"
        assertEquals("new-poster", String(Images.get(movie(), Images.Kind.POSTER)!!.bytes))
    }

    @Test fun notInPlexFallsBackToSonarr() = runBlocking {
        val s = show(); s.in_plex = false; s.plex_thumb = null; s.plex_art = null; db.saveItem(s)
        env.overrides["sonarr" to "/api/v3/mediacover/20/poster-500.jpg"] = HttpStatusCode.OK to "sonarr-poster"
        assertEquals("sonarr-poster", String(Images.get(show(), Images.Kind.POSTER)!!.bytes))
    }

    @Test fun missingImageIsRemembered() = runBlocking {
        val s = show(); s.plex_art = "/library/metadata/200/art/1"; db.saveItem(s)
        env.overrides["plex" to "/photo/:/transcode"] = HttpStatusCode.NotFound to "{}"
        assertNull(Images.get(show(), Images.Kind.ART))
        // even once it exists, the miss holds for a day
        env.overrides["plex" to "/photo/:/transcode"] = HttpStatusCode.OK to "art"
        assertNull(Images.get(show(), Images.Kind.ART))
    }

    @Test fun api() = testApplication {
        application { module() }
        val m = movie(); m.plex_thumb = "/library/metadata/100/thumb/1"; db.saveItem(m)
        env.overrides["plex" to "/photo/:/transcode"] = HttpStatusCode.OK to "poster"
        val ok = client.get("/api/items/${m.id}/image/poster?v=1")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("poster", ok.bodyAsText())
        assertTrue(ok.headers[HttpHeaders.CacheControl]!!.contains("max-age"))
        assertEquals(HttpStatusCode.UnprocessableEntity, client.get("/api/items/${m.id}/image/banner").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/items/9999/image/poster").status)
    }
}
