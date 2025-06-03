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
import no.nav.dagpenger.events.ingestion.Event
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID

class DuckDbStore(
    private val conn: Connection,
    private val periodicTrigger: PeriodicTrigger,
    private val gcsBucketEvent: String,
    private val gcsBucketAttribute: String,
    private val storage: Storage = StorageOptions.getDefaultInstance().service,
) {
    private val mutex = Mutex()

    init {
        conn.createStatement().use {
            //language=PostgreSQL
            it.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS event
                (
                    uuid       uuid PRIMARY KEY,
                    created_at TIMESTAMP,
                    event_name TEXT,
                    payload    TEXT
                );
                
                CREATE TABLE IF NOT EXISTS event_attribute
                (
                    uuid         uuid PRIMARY KEY,
                    key          TEXT,
                    type         TEXT,
                    value_string TEXT,
                    value_bool   BOOLEAN,
                    value_int    BIGINT,
                    created_at   TIMESTAMP
                );
                """.trimIndent(),
            )
        }

        periodicTrigger.register { flushToParquetAndClear() }.start()
    }

    suspend fun insertEvent(event: Event) {
        mutex.withLock {
            conn.autoCommit = false
            try {
                conn
                    .prepareStatement("INSERT INTO event (uuid, created_at, event_name, payload) VALUES (?, ?, ?, ?)")
                    .use { stmt ->
                        stmt.setObject(1, event.uuid)
                        stmt.setTimestamp(2, Timestamp.from(event.createdAt))
                        stmt.setString(3, event.eventName)
                        stmt.setString(4, event.json)
                        stmt.executeUpdate()
                    }

                event.attributes.forEach { (key, value) ->
                    conn
                        .prepareStatement(
                            """
                            INSERT INTO event_attribute (uuid, key, type, value_string, value_bool, value_int, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                        ).use { stmt ->
                            stmt.setString(1, event.eventName)
                            stmt.setString(2, key)
                            when (value) {
                                is String -> {
                                    stmt.setString(3, "string")
                                    stmt.setString(4, value)
                                    stmt.setNull(5, java.sql.Types.BOOLEAN)
                                    stmt.setNull(6, java.sql.Types.BIGINT)
                                }

                                is Boolean -> {
                                    stmt.setString(3, "boolean")
                                    stmt.setNull(4, java.sql.Types.VARCHAR)
                                    stmt.setBoolean(5, value)
                                    stmt.setNull(6, java.sql.Types.BIGINT)
                                }

                                is Long -> {
                                    stmt.setString(3, "integer")
                                    stmt.setNull(4, java.sql.Types.VARCHAR)
                                    stmt.setNull(5, java.sql.Types.BOOLEAN)
                                    stmt.setLong(6, value)
                                }

                                else -> {
                                    throw IllegalArgumentException("Unsupported attribute type: ${value::class.java}")
                                }
                            }
                            stmt.setTimestamp(7, Timestamp.from(event.createdAt))
                            stmt.executeUpdate()
                        }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }

        periodicTrigger.increment()
    }

    private suspend fun flushToParquetAndClear() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                flushTable("event", gcsBucketEvent)
                flushTable("event_attribute", gcsBucketAttribute)

                logger.info { "Flush finished" }
            }
        }

    private fun flushTable(
        table: String,
        gcsBucketPrefix: String,
    ) {
        val partition = hivePath(LocalDateTime.now())
        val gcsFile = "$gcsBucketPrefix/$partition.parquet"
        val localFile = Files.createTempFile("events-", ".parquet")
        val exportTable = "export_$table"

        logger.info { "Making copy of table=$table to flush" }

        conn.autoCommit = false
        try {
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("CREATE TABLE $exportTable AS SELECT * FROM $table")
                stmt.executeUpdate("DELETE FROM $table")
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }

        logger.info { "Exporting $table to $localFile" }

        conn.createStatement().use { stmt ->
            stmt.executeUpdate("COPY $exportTable TO '$localFile' (FORMAT 'parquet')")
            stmt.executeUpdate("DROP TABLE $exportTable")
        }

        logger.info { "Copying Parquet-file to $gcsFile" }
        copyToBucket(localFile, gcsFile)
    }

    private fun copyToBucket(
        localFile: Path,
        gcsPath: String,
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
            gcsBucketPrefixEvent: String,
            gcsBucketPrefixAttribute: String,
            trigger: PeriodicTrigger,
        ) = DuckDbStore(
            DriverManager.getConnection("jdbc:duckdb:"),
            trigger,
            gcsBucketPrefixEvent,
            gcsBucketPrefixAttribute,
        )
    }
}
