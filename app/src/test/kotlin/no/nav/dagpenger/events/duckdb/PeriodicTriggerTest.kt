package no.nav.dagpenger.events.duckdb

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class PeriodicTriggerTest {
    @Test
    fun `test trigger på batch size`() {
        var timesCalled = 0
        val trigger = PeriodicTrigger(5, 1.hours)
        trigger.register {
            timesCalled++
        }

        repeat(15) {
            trigger.increment()
        }

        runBlocking { delay(100) }
        timesCalled shouldBe 3
    }

    @Test
    fun `test trigger på max interval`() {
        var timesCalled = 0
        val trigger = PeriodicTrigger(5, 10.milliseconds)

        trigger.register {
            timesCalled++
        }

        trigger.start()

        // Må ha minst 1 rad å behandle
        trigger.increment()

        runBlocking { delay(50) }

        // Skal bare trigge på første interval
        timesCalled shouldBe 1
    }
}
