package no.nav.dagpenger.events.duckdb

import no.nav.dagpenger.events.ingestion.EventIngestor
import java.time.Instant

class DuckDbEventIngestor(
    private val store: DuckDbStore,
) : EventIngestor() {
    override suspend fun storeEvent(
        eventName: String,
        json: String,
    ) {
        store.insertEvent(Instant.now(), eventName, json)
    }
}
