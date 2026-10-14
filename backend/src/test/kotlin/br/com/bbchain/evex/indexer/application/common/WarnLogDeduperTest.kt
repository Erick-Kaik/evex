package br.com.bbchain.evex.indexer.application.common

import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test puro (sem MockK/Spring) — [WarnLogDeduper] recebe um `now` controlável pra tornar o teste determinístico, sem `Thread.sleep`. */
class WarnLogDeduperTest {

    private val key: UUID = UUID.randomUUID()

    @Test
    fun `the first occurrence of a key always logs`() {
        val deduper = WarnLogDeduper(Duration.ofMinutes(5))

        assertThat(deduper.shouldLog(key)).isTrue()
    }

    @Test
    fun `a second occurrence within the interval is suppressed`() {
        var now = Instant.parse("2026-08-24T00:00:00Z")
        val deduper = WarnLogDeduper(Duration.ofMinutes(5)) { now }

        assertThat(deduper.shouldLog(key)).isTrue()
        now = now.plusSeconds(60)
        assertThat(deduper.shouldLog(key)).isFalse()
    }

    @Test
    fun `logs again once the interval has fully elapsed since the last logged occurrence`() {
        var now = Instant.parse("2026-08-24T00:00:00Z")
        val deduper = WarnLogDeduper(Duration.ofMinutes(5)) { now }

        assertThat(deduper.shouldLog(key)).isTrue()
        now = now.plusSeconds(299)
        assertThat(deduper.shouldLog(key)).isFalse()
        now = now.plusSeconds(1) // 300s = 5min desde a última ocorrência que de fato logou
        assertThat(deduper.shouldLog(key)).isTrue()
    }

    @Test
    fun `a continuous stream of near-immediate calls never resets the countdown by itself`() {
        // Prova o cuidado documentado no KDoc de WarnLogDeduper: o carimbo só é atualizado quando a
        // resposta é `true` — senão um fluxo contínuo de eventos batendo na mesma chave a cada poucos
        // ms reiniciaria a contagem a cada chamada e o intervalo nunca "venceria" de verdade.
        var now = Instant.parse("2026-08-24T00:00:00Z")
        val deduper = WarnLogDeduper(Duration.ofMinutes(5)) { now }

        assertThat(deduper.shouldLog(key)).isTrue()
        repeat(1000) {
            now = now.plusMillis(1)
            assertThat(deduper.shouldLog(key)).isFalse()
        }
        now = now.plusSeconds(300)
        assertThat(deduper.shouldLog(key)).isTrue()
    }

    @Test
    fun `different keys are tracked independently`() {
        val otherKey = UUID.randomUUID()
        val deduper = WarnLogDeduper(Duration.ofMinutes(5))

        assertThat(deduper.shouldLog(key)).isTrue()
        assertThat(deduper.shouldLog(key)).isFalse()
        assertThat(deduper.shouldLog(otherKey)).isTrue()
    }

    @Test
    fun `string keys follow the same window rule and never collide with UUID keys`() {
        var now = Instant.parse("2026-08-24T00:00:00Z")
        val deduper = WarnLogDeduper(Duration.ofMinutes(5)) { now }

        assertThat(deduper.shouldLog("contract|method|RPC_ERROR")).isTrue()
        assertThat(deduper.shouldLog("contract|method|RPC_ERROR")).isFalse()
        assertThat(deduper.shouldLog("contract|other|RPC_ERROR")).isTrue()
        assertThat(deduper.shouldLog(key)).isTrue()
        now = now.plusSeconds(300)
        assertThat(deduper.shouldLog("contract|method|RPC_ERROR")).isTrue()
    }

    @Test
    fun `occurrencesIfDue reports how many occurrences the next log line represents`() {
        var now = Instant.parse("2026-08-24T00:00:00Z")
        val deduper = WarnLogDeduper(Duration.ofMinutes(5)) { now }

        assertThat(deduper.occurrencesIfDue("k")).isEqualTo(1)
        assertThat(deduper.occurrencesIfDue("k")).isNull()
        assertThat(deduper.occurrencesIfDue("k")).isNull()
        now = now.plusSeconds(300)
        assertThat(deduper.occurrencesIfDue("k")).isEqualTo(3) // 2 suprimidas + esta
        assertThat(deduper.occurrencesIfDue("k")).isNull()
    }
}

