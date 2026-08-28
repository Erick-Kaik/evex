package br.com.bbchain.evex.indexer.domain.blockchain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit test puro (sem I/O, sem mocks) — 4 cenários de `ingestion-pipeline-reference.md` §1, mais
 * variações de borda.
 */
class PollWindowTest {

    @Test
    fun `advances from lastProcessed plus one and clamps to by batchSize when the distance is larger`() {
        val window = PollWindow.next(lastProcessed = 0, latest = 10_000, confirmations = 0, batchSize = 100)

        assertThat(window).isEqualTo(1L..100L)
    }

    @Test
    fun `processes the whole remaining distance in one window when it fits inside batchSize`() {
        val window = PollWindow.next(lastProcessed = 100, latest = 200, confirmations = 0, batchSize = 1000)

        assertThat(window).isEqualTo(101L..200L)
    }

    @Test
    fun `returns null when there is no confirmed block pending (lastProcessed caught up to safeHead)`() {
        val window = PollWindow.next(lastProcessed = 100, latest = 100, confirmations = 0, batchSize = 1000)

        assertThat(window).isNull()
    }

    @Test
    fun `returns null when confirmations push safeHead below the next block to process`() {
        val window = PollWindow.next(lastProcessed = 100, latest = 103, confirmations = 5, batchSize = 1000)

        assertThat(window).isNull()
    }

    @Test
    fun `processes exactly one block when from equals safeHead`() {
        val window = PollWindow.next(lastProcessed = 99, latest = 100, confirmations = 0, batchSize = 1000)

        assertThat(window).isEqualTo(100L..100L)
    }

    @Test
    fun `starts from block zero when nothing has been processed yet (lastProcessed = -1)`() {
        val window = PollWindow.next(lastProcessed = -1, latest = 5, confirmations = 0, batchSize = 1000)

        assertThat(window).isEqualTo(0L..5L)
    }

    @Test
    fun `respects confirmations when computing safeHead even within batchSize`() {
        val window = PollWindow.next(lastProcessed = 0, latest = 110, confirmations = 10, batchSize = 1000)

        assertThat(window).isEqualTo(1L..100L)
    }
}
