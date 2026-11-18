package karlstodian

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime
import kotlin.jvm.optionals.getOrNull

/*
 * HTTP API and pages. Read-only toward the services: run scans and look at the cached data.
 */

private val log = LoggerFactory.getLogger("karlstodian")

/** The open database. Tests call reset() after pointing PRUNER_DB_PATH somewhere else. */
object Store {
    @Volatile private var opened: Db? = null
    val db: Db
        get() = opened ?: synchronized(this) { opened ?: Db(Config.current.db_path).also { opened = it } }

    fun reset() = synchronized(this) { opened?.close(); opened = null }
}

class HttpError(val status: HttpStatusCode, override val message: String) : Exception(message)

// ------------------------------------------------------------------ scheduled scans

object Scheduler {
    private val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
    private var job: Job? = null
    @Volatile var nextRun: ZonedDateTime? = null
        private set

    fun validate(expr: String) {
        if (expr.isEmpty()) return
        try {
            parser.parse(expr).validate()
        } catch (e: IllegalArgumentException) {
            throw SettingsError("scan_cron: ${e.message}")
        }
    }

    /** (Re)start the timer from the current settings. */
    @Synchronized
    fun reschedule(scope: CoroutineScope) {
        job?.cancel()
        nextRun = null
        val expr = Config.current.scan_cron
        if (expr.isEmpty()) return
        val exec = ExecutionTime.forCron(parser.parse(expr))
        nextRun = exec.nextExecution(ZonedDateTime.now()).getOrNull()
        job = scope.launch {
            while (isActive) {
                val next = exec.nextExecution(ZonedDateTime.now()).getOrNull() ?: return@launch
                nextRun = next
                delay(java.time.Duration.between(ZonedDateTime.now(), next).toMillis().coerceAtLeast(0))
                try {
                    runScan()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("scheduled scan failed", e)
                }
            }
        }
    }

    @Synchronized
    fun stop() { job?.cancel(); job = null; nextRun = null }
}

// ------------------------------------------------------------------ items

private val SORTS: Map<String, Pair<(JsonObject) -> Comparable<*>?, Boolean>> = mapOf( // (key, descending); blanks last
    "score" to ({ o: JsonObject -> o.double("score") } to true),
    "size" to ({ o: JsonObject -> o.long("size_bytes") } to true),
    "age" to ({ o: JsonObject -> o.str("plex_added_at") } to false),
    "plays" to ({ o: JsonObject -> o.long("play_count") } to false),
    "last_played" to ({ o: JsonObject -> o.str("last_played_at") } to false),
    "resolution" to ({ o: JsonObject -> o.long("min_resolution") } to false),
    "title" to ({ o: JsonObject -> o.str("title")?.lowercase() } to false),
)

private fun tagsOf(i: Item): List<String> = json.parseToJsonElement(i.arr_tags).arr.map { it.jsonPrimitive.content }

private fun inputOf(i: Item, cfg: Settings): ScoreInput {
    val tags = tagsOf(i)
    return ScoreInput(i, i.keep || cfg.keep_tag in tags, i.quality_exempt || cfg.quality_ok_tag in tags)
}

/** An item as the API returns it: its columns, tags as a list, effective flags and its score. */
private fun itemOut(input: ScoreInput, score: Score): JsonObject {
    val base = json.encodeToJsonElement(Item.serializer(), input.item).jsonObject.toMutableMap()
    base["arr_tags"] = JsonArray(tagsOf(input.item).map(::JsonPrimitive))
    base["keep_effective"] = JsonPrimitive(input.keepEffective)
    base["quality_exempt_effective"] = JsonPrimitive(input.qualityExemptEffective)
    val s = json.encodeToJsonElement(Score.serializer(), score).jsonObject
    return JsonObject(base + s)
}

/** All items with their scores. Scoring needs the whole library (size is a percentile). */
fun scoredItems(db: Db = Store.db, cfg: Settings = Config.current): List<JsonObject> {
    val inputs = db.items().map { inputOf(it, cfg) }
    val scores = scoreItems(inputs, cfg)
    return inputs.map { itemOut(it, scores.getValue(it.item.id!!)) }
}

@Suppress("UNCHECKED_CAST")
fun sortItems(items: List<JsonObject>, sort: String): List<JsonObject> {
    val (key, desc) = SORTS[sort] ?: throw HttpError(HttpStatusCode.UnprocessableEntity, "sort must be one of ${SORTS.keys}")
    val present = items.filter { key(it) != null }
    val cmp = compareBy<JsonObject> { key(it) as Comparable<Any> }
    return present.sortedWith(if (desc) cmp.reversed() else cmp) + items.filter { key(it) == null }
}

// ------------------------------------------------------------------ app

private fun page(name: String): String =
    Store::class.java.getResource("/static/$name")!!.readText()

fun Application.module() {
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<HttpError> { call, e -> call.respond(e.status, buildJsonObject { put("detail", e.message) }) }
        exception<SettingsError> { call, e -> call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("detail", e.message) }) }
        exception<kotlinx.serialization.SerializationException> { call, e ->
            call.respond(HttpStatusCode.UnprocessableEntity, buildJsonObject { put("detail", e.message ?: "invalid body") })
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.UnprocessableEntity, buildJsonObject { put("detail", e.message ?: "invalid request") })
        }
    }

    Store.db // open + migrate on startup
    Scheduler.reschedule(this)
    monitor.subscribe(ApplicationStopping) { Scheduler.stop() }

    suspend fun ApplicationCall.body(): JsonObject {
        val text = receiveText()
        if (text.isBlank()) return JsonObject(emptyMap())
        return json.parseToJsonElement(text) as? JsonObject ?: throw HttpError(HttpStatusCode.UnprocessableEntity, "expected a JSON object")
    }

    fun settingsOut() = buildJsonObject {
        put("values", Config.publicView())
        put("next_scan", Scheduler.nextRun?.toOffsetDateTime()?.toString())
    }

    fun itemId(call: ApplicationCall): Long =
        call.parameters["id"]?.toLongOrNull() ?: throw HttpError(HttpStatusCode.UnprocessableEntity, "id must be a number")

    routing {
        // pages
        get("/") { call.respondRedirect("/library") }
        for (p in listOf("library", "settings")) {
            get("/$p") {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondText(page("$p.html"), ContentType.Text.Html)
            }
        }
        get("/healthz") { call.respond(buildJsonObject { put("ok", true) }) }

        // settings
        get("/api/settings") { call.respond(settingsOut()) }

        /** Partial update. Blank secrets keep the saved value; `_clear: [field]` removes one. */
        put("/api/settings") {
            val new = Config.merge(call.body())
            Scheduler.validate(new.scan_cron)
            Config.apply(new)
            Scheduler.reschedule(this@module)
            call.respond(settingsOut())
        }

        /** Test one service with the (possibly unsaved) values from the form. */
        post("/api/settings/test/{service}") {
            val service = call.parameters["service"]!!
            val fields = SERVICES[service] ?: throw HttpError(HttpStatusCode.NotFound, "unknown service")
            val allowed = fields.toSet() + setOf("verify_ssl", "_clear")
            val candidate = try {
                Config.merge(JsonObject(call.body().filterKeys { it in allowed }))
            } catch (e: SettingsError) {
                return@post call.respond(buildJsonObject {
                    put("service", service); put("ok", false); put("configured", true); put("error", e.message)
                })
            }
            call.respond(checkService(service, candidate))
        }

        get("/api/check") { call.respond(JsonArray(checkAll())) }

        // scans
        post("/api/scan") {
            if (scanLock.isLocked) return@post call.respond(HttpStatusCode.Accepted, buildJsonObject { put("status", "already_running") })
            this@module.launch {
                try { runScan() } catch (e: Exception) { log.error("scan failed", e) }
            }
            call.respond(HttpStatusCode.Accepted, buildJsonObject { put("status", "started") })
        }

        get("/api/scans") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            call.respond(JsonArray(Store.db.scanRuns(limit).map { r ->
                val o = json.encodeToJsonElement(ScanRun.serializer(), r).jsonObject
                JsonObject(o + ("summary" to json.parseToJsonElement(r.summary)))
            }))
        }

        // items
        get("/api/items") {
            val q = call.request.queryParameters
            val kind = q["kind"]
            if (kind != null && kind !in setOf("movie", "show")) throw HttpError(HttpStatusCode.UnprocessableEntity, "kind must be movie or show")
            val limit = q["limit"]?.toIntOrNull() ?: 100
            val offset = q["offset"]?.toIntOrNull() ?: 0
            var items = scoredItems()
            if (kind != null) items = items.filter { it.str("kind") == kind }
            items = sortItems(items, q["sort"] ?: "score")
            call.respond(buildJsonObject {
                put("total", items.size)
                put("weights", JsonObject(Config.current.score_weights.mapValues { JsonPrimitive(it.value) }))
                put("items", JsonArray(items.drop(offset).take(limit)))
            })
        }

        get("/api/items/{id}") {
            val id = itemId(call)
            val db = Store.db
            db.item(id) ?: throw HttpError(HttpStatusCode.NotFound, "Not Found")
            val scored = scoredItems(db).first { it.long("id") == id }
            val files = db.files(id)
            val hashes = files.mapNotNull { it.torrent_hash }.toSet()
            call.respond(JsonObject(scored + mapOf(
                "files" to json.encodeToJsonElement(files),
                "torrents" to json.encodeToJsonElement(db.torrents(hashes)),
                "user_plays" to json.encodeToJsonElement(db.userPlays(id)),
            )))
        }

        /** Set the keep / quality-exempt flags (they survive rescans). */
        patch("/api/items/{id}") {
            val id = itemId(call)
            val body = call.body()
            val db = Store.db
            val item = db.item(id) ?: throw HttpError(HttpStatusCode.NotFound, "Not Found")
            fun flag(name: String): Boolean? {
                val v = body[name] ?: return null
                if (v is JsonNull) return null
                return (v as? JsonPrimitive)?.booleanOrNull ?: throw HttpError(HttpStatusCode.UnprocessableEntity, "$name must be true or false")
            }
            flag("keep")?.let { item.keep = it }
            flag("quality_exempt")?.let { item.quality_exempt = it }
            db.saveItem(item)
            call.respond(scoredItems(db).first { it.long("id") == id })
        }
    }
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "check" -> return runCheckCli()
        "scan" -> return runScanCli()
    }
    val host = System.getenv("PRUNER_HOST") ?: "127.0.0.1"
    val port = System.getenv("PRUNER_PORT")?.toIntOrNull() ?: 8000
    log.info("Karlstodian on http://$host:$port")
    embeddedServer(CIO, host = host, port = port, module = Application::module).start(wait = true)
}
