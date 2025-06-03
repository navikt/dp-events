package no.nav.dagpenger.events.duckdb

import no.nav.dagpenger.events.ingestion.Event
import no.nav.dagpenger.events.ingestion.EventIngestor

class DuckDbEventIngestor(
    private val store: DuckDbStore,
) : EventIngestor() {
    override suspend fun storeEvent(event: Event) {
        store.insertEvent(event)
    }
}
