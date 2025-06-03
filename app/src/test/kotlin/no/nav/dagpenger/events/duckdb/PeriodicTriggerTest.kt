package no.nav.dagpenger.events.duckdb

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PeriodicTriggerTest {
    @Test
    fun `test trigger på batch size`() {
        var antallBatcher = 0
        val trigger =
            PeriodicTrigger(5, 1.hours) {
                antallBatcher++
            }

        repeat(15) {
            // Simulerer at det kommer inn 15 rader
            trigger.onInsert()
        }

        // Vent på at alle rader er prosessert
        ventPåAntallBatcher({ antallBatcher }, 3)

        // Sjekk at det er laget tre batcher
        antallBatcher shouldBe 3
    }

    @Test
    fun `test trigger på max interval`() {
        var antallBatcher = 0
        val trigger = PeriodicTrigger(5, 10.milliseconds) { antallBatcher++ }

        trigger.start()

        // Må ha minst 1 rad å behandle
        trigger.onInsert()

        // Vent på at intervallet skal utløpe
        ventPåAntallBatcher({ antallBatcher }, 1, timeout = 100.milliseconds)

        // Skal bare trigge på første interval
        antallBatcher shouldBe 1
    }

    @Test
    fun `test at den flusher ved stop`() {
        var antallBatcher = 0
        val trigger = PeriodicTrigger(5, 10.seconds) { antallBatcher++ }

        trigger.start()

        // Må ha minst 1 rad å behandle
        trigger.onInsert()

        // Stopper triggeren før intervallet er utløptrr
        trigger.stop()

        // Skal bare trigge på første interval
        antallBatcher shouldBe 1
    }

    private fun ventPåAntallBatcher(
        antallBatcher: () -> Int,
        forventetAntall: Int,
        timeout: Duration = 500.milliseconds,
    ) {
        runBlocking {
            withTimeout(timeout) {
                while (antallBatcher() < forventetAntall) {
                    delay(5)
                }
            }
        }
    }
}
