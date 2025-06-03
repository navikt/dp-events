package no.nav.dagpenger.events.ingestion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

abstract class EventIngestor {
    abstract suspend fun storeEvent(event: Event)

    suspend fun handleEvent(json: String) {
        val event =
            try {
                val parsed = Json.parseToJsonElement(json).jsonObject

                val eventName = parsed["event_name"]?.jsonPrimitive?.contentOrNull
                if (eventName.isNullOrBlank()) throw IllegalArgumentException("Missing 'event_name'")

                val attributes = parsed["payload"]?.let { flatJsonToMap(it) } ?: emptyMap()

                Event(eventName, attributes, json)
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed JSON: ${e.message}")
            }

        storeEvent(event)
    }

    private fun flatJsonToMap(json: JsonElement): Map<String, Any> {
        require(json is JsonObject) { "JSON må være et objekt på toppnivå." }

        return json.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive ->
                    when {
                        value.isString -> value.content
                        value.booleanOrNull != null -> value.boolean
                        value.longOrNull != null -> value.long
                        value.doubleOrNull != null -> value.double
                        else -> value.content // fallback
                    }

                else -> value.toString() // serialiser arrays/objects som string
            }
        }
    }
}
