package no.nav.dagpenger.events.ingestion

import com.github.f4b6a3.uuid.UuidCreator
import java.time.Instant
import java.util.UUID

data class Event(
    val eventName: String,
    val attributes: Map<String, Any>,
    val json: String,
) {
    val uuid: UUID = UuidCreator.getTimeOrderedEpoch()
    val createdAt: Instant = Instant.now()
}
