package karlstodian

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.time.Instant

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

val prettyJson = Json(json) { prettyPrint = true }

/** Instants go over the API as ISO-8601 UTC, e.g. 2026-10-24T09:00:53Z. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

// Small helpers for reading the services' loosely typed JSON

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray get() = (this as? JsonArray) ?: JsonArray(emptyList())
fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonObject?.int(key: String): Int? = long(key)?.toInt()
fun JsonObject?.double(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
fun JsonObject?.child(key: String): JsonObject? = this?.get(key) as? JsonObject

/** Python-style truthiness for the "x or y" fallbacks the services need. */
fun String?.orIfBlank(other: () -> String?): String? = if (this.isNullOrEmpty()) other() else this
