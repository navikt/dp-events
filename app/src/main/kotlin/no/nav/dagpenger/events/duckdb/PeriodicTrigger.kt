package no.nav.dagpenger.events.duckdb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun interface TriggerAction {
    suspend fun invoke()
}

class PeriodicTrigger(
    private val batchSize: Int,
    private val interval: Duration,
    private val action: TriggerAction,
) : DuckDbObserver {
    private val counter = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var flushJob: Job? = null

    internal fun start() {
        logger.info { "Starter å regelmessig flushe events som er færre en batch-størrelse" }
        scheduleIntervalFlush()
    }

    internal fun stop() {
        logger.info { "Avslutter regelmessig flushing" }
        flushJob?.cancel()
        if (counter.get() > 0) {
            runBlocking {
                flushSafely()
            }
        }
        scope.cancel()
    }

    private fun increment() {
        val newValue = counter.addAndGet(1)
        if (newValue >= batchSize) {
            // Atomically claim all current events for flushing
            val eventsToFlush = counter.getAndSet(0)
            if (eventsToFlush > 0) {
                logger.info { "Flusher data etter batch har nådd $eventsToFlush events" }
                scope.launch {
                    flushSafely()
                }
            }
        }
    }

    override fun onInsert() = increment()

    fun registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("Shutdown hook triggered. Cleaning up...")
                stop()
                logger.info("Cleanup complete.")
            },
        )
    }

    private fun scheduleIntervalFlush() {
        flushJob?.cancel() // Cancel any previous timer
        flushJob =
            scope.launch {
                delay(interval.withJitter((500.milliseconds)))
                logger.info { "Sjekker etter $interval om det skal flushes. Har ${counter.get()} events." }
                if (counter.get() == 0) {
                    // No need to flush if counter is zero
                    scheduleIntervalFlush()
                    return@launch
                }
                flushSafely()
                counter.set(0)
            }
    }

    private suspend fun flushSafely() {
        try {
            action.invoke()
        } catch (e: Exception) {
            logger.error(e) { "Feilet å flushe data: ${e.message}" }
            throw e
        } finally {
            scheduleIntervalFlush() // Reschedule after successful flush
        }
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }

    private fun Duration.withJitter(tolerance: Duration) =
        this + (-tolerance.inWholeMilliseconds..tolerance.inWholeMilliseconds).random().milliseconds
}
