package no.nav.dagpenger.events.duckdb

import com.google.cloud.storage.Storage
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import no.nav.dagpenger.events.ingestion.Event
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.Timestamp

class DuckDbStoreTest {
    private val connection = DriverManager.getConnection("jdbc:duckdb:")
    private val periodicTrigger = TestTrigger()
    private val storage = mockk<Storage>(relaxed = true)

    private val duckDbStore =
        DuckDbStore(connection, periodicTrigger, "gs://test-bucket/event", "gs://test-bucket/attribute", storage)

    @Test
    fun `insertEvent should insert event into database`() {
        val eventName = "some_event"
        val payload = "{\"key\": \"value\"}"

        val attributes =
            mapOf(
                "string" to "value",
                "boolean" to true,
                "number" to 42.0,
            )
        val event = Event(eventName, attributes, payload)

        runBlocking { duckDbStore.insertEvent(event) }

        connection.prepareStatement("SELECT * FROM event").use {
            val rs = it.executeQuery()
            while (rs.next()) {
                rs.getObject(1) shouldBe event.uuid
                rs.getTimestamp(2) shouldBe Timestamp.from(event.createdAt)
                rs.getString(3) shouldBe event.eventName
                rs.getString(4) shouldBe event.json
            }
        }

        periodicTrigger.counter shouldBe 1
        periodicTrigger.trigger()

        verify(exactly = 2) {
            storage.createFrom(any(), any<Path>())
        }
    }

    private class TestTrigger : IPeriodicTrigger {
        var counter: Int = 0
        var action: suspend () -> Unit = {}

        fun trigger() {
            runBlocking { action() }
        }

        override fun register(block: suspend () -> Unit): IPeriodicTrigger {
            action = block
            return this
        }

        override fun increment() {
            counter++
        }

        override fun start() {
        }

        override fun stop() {
        }
    }
}
