package no.nav.dagpenger.events

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import mu.KotlinLogging
import no.nav.dagpenger.events.api.eventApi
import no.nav.dagpenger.events.duckdb.DuckDbEventIngestor
import no.nav.dagpenger.events.duckdb.DuckDbStore
import no.nav.dagpenger.events.duckdb.PeriodicTrigger
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun main() {
    val batchSize = System.getenv("GCS_BATCH_SIZE")?.toIntOrNull() ?: 1000
    val maxInterval = System.getenv("GCS_MAX_INTERVAL_SECONDS")?.toIntOrNull() ?: 10
    val gcsBucketPrefixEvent =
        System.getenv("GCS_BUCKET_PREFIX_EVENT")
            ?: throw IllegalArgumentException("Environment variable GCS_BUCKET_PREFIX_EVENT is not set")
    val gcsBucketPrefixAttribute =
        System.getenv("GCS_BUCKET_PREFIX_ATTRIBUTE")
            ?: throw IllegalArgumentException("Environment variable GCS_BUCKET_PREFIX_ATTRIBUTE is not set")

    val trigger =
        PeriodicTrigger(batchSize, maxInterval.seconds).apply {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    logger.info("Shutdown hook triggered. Cleaning up...")
                    stop()
                    logger.info("Cleanup complete.")
                },
            )
        }
    val store =
        DuckDbStore.createInMemoryStore(
            gcsBucketPrefixEvent,
            gcsBucketPrefixAttribute,
            trigger,
        )
    val ingestor = DuckDbEventIngestor(store)

    embeddedServer(CIO, port = 8080) {
        eventApi(ingestor)
    }.apply {
        logger.info { "Starting server" }
        start(wait = true)
    }
}
