package no.nav.dagpenger.events.ingestion

abstract class EventIngestor {
    abstract suspend fun storeEvent(event: Event)

    suspend fun handleEvent(json: String) {
        val event = Event.fraJson(json)

        storeEvent(event)
    }
}
