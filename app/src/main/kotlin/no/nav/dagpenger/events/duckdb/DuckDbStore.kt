package no.nav.dagpenger.events.duckdb

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class DuckDbStore(
    private val conn: Connection,
    private val periodicTrigger: PeriodicTrigger,
    gcsBucketPrefix: String,
    private val storage: Storage = StorageOptions.getDefaultInstance().service,
) {
    private val mutex = Mutex()

    init {
        conn.createStatement().use {
            //language=GenericSQL
            it.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS events (
                    ts TIMESTAMP,
                    event_name TEXT,
                    payload TEXT,
                    collected_by TEXT, 
                )
                """.trimIndent(),
            )
        }

        periodicTrigger.register { flushToParquetAndClear(gcsBucketPrefix) }.start()
    }

    private val appName by lazy {
        System.getenv("NAIS_APP_NAME") ?: "unknown-app"
    }

    suspend fun insertEvent(
        ts: Instant,
        eventName: String,
        payload: String,
    ) {
        mutex.withLock {
            conn
                .prepareStatement("INSERT INTO events (ts, event_name, payload, collected_by) VALUES (?, ?, ?, ?)")
                .use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(ts))
                    stmt.setString(2, eventName)
                    stmt.setString(3, payload)
                    stmt.setString(4, appName)
                    stmt.executeUpdate()
                }
        }

        periodicTrigger.increment()
    }

    private suspend fun flushToParquetAndClear(bucketPathPrefix: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val partition = hivePath(LocalDateTime.now())
                val gcsFile = "$bucketPathPrefix/$partition.parquet"
                val localFile = Files.createTempFile("events-", ".parquet")

                logger.info { "Making copy of events-table to flush" }

                conn.autoCommit = false
                try {
                    conn.createStatement().use { stmt ->
                        stmt.executeUpdate("CREATE TABLE to_export AS SELECT * FROM events")
                        stmt.executeUpdate("DELETE FROM events")
                    }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                }

                logger.info { "Exporting events to $localFile" }

                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("COPY to_export TO '$localFile' (FORMAT 'parquet')")
                    stmt.executeUpdate("DROP TABLE to_export")
                }

                logger.info { "Copying Parquet-file to $gcsFile" }
                copyToBucket(gcsFile, localFile)

                logger.info { "Flush finished" }
            }
        }

    private fun copyToBucket(
        gcsPath: String,
        localFile: Path,
    ) {
        val blobId = BlobId.fromGsUtilUri(gcsPath)
        val blobInfo = BlobInfo.newBuilder(blobId).build()
        storage.createFrom(blobInfo, localFile)
    }

    private fun hivePath(now: LocalDateTime = LocalDateTime.now()) =
        "year=${now.year}/month=${now.month.value}/day=${now.dayOfMonth}/${UUID.randomUUID()}"

    companion object {
        private val logger = KotlinLogging.logger { }

        fun createInMemoryStore(
            gcsBucketPrefix: String,
            trigger: PeriodicTrigger,
        ) = DuckDbStore(
            DriverManager.getConnection("jdbc:duckdb:"),
            trigger,
            gcsBucketPrefix,
        )
    }
}
