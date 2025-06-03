package no.nav.dagpenger.events.ingestion

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

internal class EventIngestorTest {
    @Test
    fun `skal lagre event for gyldig JSON med event_name `() {
        val json = """{"event_name": "test_event"}"""
        val ingestor = TestEventIngestor()

        runBlocking { ingestor.handleEvent(json) }

        ingestor.storedEvents.size shouldBe 1
        ingestor.storedEvents.first().first shouldBe "test_event"
        ingestor.storedEvents.first().second shouldContain "test_event"
    }

    @Test
    fun `skal kaste exception når JSON ikke inneholder event_name`() {
        val json = """{"data": "value"}"""
        val ingestor = TestEventIngestor()

        val exception =
            shouldThrow<IllegalArgumentException> {
                runBlocking { ingestor.handleEvent(json) }
            }
        exception.message shouldContain "Missing 'event_name'"
    }

    @Test
    fun `skal kaste exception når event_name er tom`() {
        // Arrange
        val json = """{"event_name": ""}"""
        val ingestor = TestEventIngestor()

        // Act & Assert
        val exception =
            shouldThrow<IllegalArgumentException> {
                runBlocking { ingestor.handleEvent(json) }
            }
        exception.message shouldContain "Missing 'event_name'"
    }

    @Test
    fun `skal kaste exception når JSON ikke er gyldig`() {
        // Arrange
        val json = """{"event_name":"""
        val ingestor = TestEventIngestor()

        // Act & Assert
        val exception =
            shouldThrow<IllegalArgumentException> {
                runBlocking { ingestor.handleEvent(json) }
            }
        exception.message shouldContain "Malformed JSON"
    }

    private class TestEventIngestor : EventIngestor() {
        val storedEvents = mutableListOf<Pair<String, String>>()

        override suspend fun storeEvent(
            eventName: String,
            json: String,
        ) {
            storedEvents.add(eventName to json)
        }
    }
}
