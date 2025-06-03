package no.nav.dagpenger.events.duckdb

import com.google.cloud.storage.Storage
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import no.nav.dagpenger.events.ingestion.Event
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp

class DuckDbStoreTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:duckdb:")
    private val mockPeriodicTrigger: PeriodicTrigger = mockk(relaxed = true)
    private val storage = mockk<Storage>(relaxed = true)

    private val duckDbStore =
        DuckDbStore(connection, mockPeriodicTrigger, "test-bucket1", "test-bucket2", storage)

    @AfterEach
    fun tearDown() {
        clearMocks(mockPeriodicTrigger)
    }

    @Test
    fun `insertEvent should insert event into database`() {
        val eventName = "some_event"
        val payload = "{\"key\": \"value\"}"

        val event = Event(eventName, emptyMap(), payload)
        runBlocking {
            duckDbStore.insertEvent(event)
        }

        connection.prepareStatement("SELECT * FROM event").use {
            val rs = it.executeQuery()
            while (rs.next()) {
                rs.getObject(1) shouldBe event.uuid
                rs.getTimestamp(2) shouldBe Timestamp.from(event.createdAt)
                rs.getString(3) shouldBe event.eventName
                rs.getString(4) shouldBe event.json
            }
        }
        verify { mockPeriodicTrigger.increment() }
    }
}
