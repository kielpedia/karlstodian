package karlstodian

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/** Settings from environment variables (Kubernetes Secrets) win over settings.json and are never written to it. */
class ConfigEnvTest {
    private val dir = Files.createTempDirectory("karlstodian-env")

    @AfterTest fun tearDown() {
        Config.reload(emptyMap())
        dir.toFile().deleteRecursively()
    }

    @Test fun dataFolderFromEnv() {
        Config.reload(mapOf("KARLSTODIAN_DATA" to dir.toString()))
        assertEquals(dir.resolve("pruner.db").toString(), Config.current.db_path)
        assertEquals(dir.resolve("settings.json").toAbsolutePath(), Config.settingsFile())
        // an explicit database path still wins
        Config.reload(mapOf("KARLSTODIAN_DATA" to dir.toString(), "PRUNER_DB_PATH" to dir.resolve("x.db").toString()))
        assertEquals(dir.resolve("x.db").toString(), Config.current.db_path)
    }

    @Test fun envWinsOverSavedSettingsAndIsLocked() {
        Files.writeString(dir.resolve("settings.json"), """{"radarr_url": "http://saved", "radarr_api_key": "old", "grace_days": 7}""")
        Config.reload(mapOf(
            "KARLSTODIAN_DATA" to dir.toString(),
            "KARLSTODIAN_RADARR_URL" to "http://radarr", "PRUNER_RADARR_URL" to "http://ignored",
            "KARLSTODIAN_RADARR_API_KEY" to "from-secret",
        ))
        assertEquals("http://radarr", Config.current.radarr_url) // KARLSTODIAN_ beats PRUNER_ and the file
        assertEquals("from-secret", Config.current.radarr_api_key)
        assertEquals(7, Config.current.grace_days) // not set by env: the saved value
        assertEquals(setOf("radarr_url", "radarr_api_key"), Config.envFields)

        // the settings page can't change them, and saving never writes them to settings.json
        Config.apply(Config.merge(buildJsonObject {
            put("radarr_url", "http://evil"); put("radarr_api_key", "x"); put("grace_days", 10)
        }))
        assertEquals("http://radarr", Config.current.radarr_url)
        assertEquals("from-secret", Config.current.radarr_api_key)
        assertEquals(10, Config.current.grace_days)
        val file = json.parseToJsonElement(Files.readString(dir.resolve("settings.json"))).jsonObject
        assertFalse("radarr_url" in file); assertFalse("radarr_api_key" in file)
        assertEquals(10, file["grace_days"]!!.jsonPrimitive.int)
    }
}
