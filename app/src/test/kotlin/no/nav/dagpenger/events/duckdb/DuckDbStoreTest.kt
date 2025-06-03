package no.nav.dagpenger.events.duckdb

import com.google.cloud.storage.Storage
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant

class DuckDbStoreTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:duckdb:")
    private val mockPeriodicTrigger: PeriodicTrigger = mockk(relaxed = true)
    private val storage = mockk<Storage>(relaxed = true)

    private val duckDbStore =
        DuckDbStore(connection, mockPeriodicTrigger, "test-bucket", storage)

    @AfterEach
    fun tearDown() {
        clearMocks(mockPeriodicTrigger)
    }

    @Test
    fun `insertEvent should insert event into database`() {
        val timestamp = Instant.now()
        val eventName = "some_event"
        val payload = "{\"key\": \"value\"}"

        runBlocking {
            duckDbStore.insertEvent(timestamp, eventName, payload)
        }

        connection.prepareStatement("SELECT * FROM events").use {
            val rs = it.executeQuery()
            while (rs.next()) {
                rs.getTimestamp(1) shouldBe Timestamp.from(timestamp)
                rs.getString(2) shouldBe eventName
                rs.getString(3) shouldBe payload
                rs.getString(4) shouldBe "unknown-app"
            }
        }
        verify { mockPeriodicTrigger.increment() }
    }
}
