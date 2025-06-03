package no.nav.dagpenger.events.duckdb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

interface IPeriodicTrigger {
    fun register(block: suspend () -> Unit): IPeriodicTrigger

    fun increment()

    fun start()

    fun stop()
}

class PeriodicTrigger(
    private val batchSize: Int,
    private val interval: Duration,
) : IPeriodicTrigger {
    private var action: suspend () -> Unit = {}
    private val counter = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var flushJob: Job? = null

    override fun start() {
        scheduleIntervalFlush()
    }

    override fun stop() {
        flushJob?.cancel()
        scope.launch {
            flushSafely()
        }
        scope.cancel()
    }

    override fun increment() {
        val newValue = counter.addAndGet(1)
        if (newValue >= batchSize) {
            scope.launch {
                flushSafely()
            }
            counter.set(0)
        }
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
            action()
        } finally {
            scheduleIntervalFlush() // Reschedule after successful flush
        }
    }

    override fun register(block: suspend () -> Unit): PeriodicTrigger {
        this.action = block
        return this
    }
}
