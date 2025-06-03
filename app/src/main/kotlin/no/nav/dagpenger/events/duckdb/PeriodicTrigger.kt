package no.nav.dagpenger.events.duckdb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

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
        scheduleIntervalFlush()
    }

    internal fun stop() {
        flushJob?.cancel()
        scope.launch {
            if (counter.get() == 0) return@launch // No need to flush if counter is zero
            flushSafely()
        }
        scope.cancel()
    }

    private fun increment() {
        val newValue = counter.addAndGet(1)
        if (newValue >= batchSize) {
            scope.launch {
                flushSafely()
            }
            counter.set(0)
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
                delay(interval)
                if (counter.get() == 0) return@launch // No need to flush if counter is zero
                flushSafely()
                counter.set(0)
            }
    }

    private suspend fun flushSafely() {
        try {
            action.invoke()
        } catch (e: Exception) {
            logger.error(e) { "Failed to flush data: ${e.message}" }
            throw e
        } finally {
            scheduleIntervalFlush() // Reschedule after successful flush
        }
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
