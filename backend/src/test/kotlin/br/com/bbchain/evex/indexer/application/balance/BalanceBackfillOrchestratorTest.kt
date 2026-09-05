package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.domain.balance.BalanceBackfillStatus
import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.jobstatus.JobStatusPublisherPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit test — [BalanceBackfillRunner] mockado via MockK (sem `@Async` real). Cobre a guarda de
 * 1 backfill por vez (global) e o status **global** do job (DEC-067): `configId` da execução em
 * andamento, contadores acumulados e mantidos após `onFinished`, `remainingCount` ao vivo.
 */
class BalanceBackfillOrchestratorTest {

    private val runner: BalanceBackfillRunner = mockk(relaxed = true)
    private val events: BlockchainEventPort = mockk()
    private val configs: BalanceMonitorConfigPort = mockk()
    private val statusPublisher: JobStatusPublisherPort<BalanceBackfillStatus> = mockk(relaxed = true)
    private lateinit var orchestrator: BalanceBackfillOrchestrator

    private val configA = UUID.randomUUID()
    private val configB = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        orchestrator = BalanceBackfillOrchestrator(runner, events, configs, statusPublisher)
        every { configs.findById(any()) } answers { sampleConfig(firstArg()) }
        every { events.countDecodedForBackfill(any(), any(), any(), any(), any(), any()) } returns 0L
    }

    @Test
    fun `tryStart returns true the first time and dispatches the runner`() {
        val started = orchestrator.tryStart(configA)

        assertThat(started).isTrue()
        verify(exactly = 1) { runner.run(configA, orchestrator) }
        assertThat(orchestrator.globalStatus().running).isTrue()
        assertThat(orchestrator.globalStatus().configId).isEqualTo(configA)
    }

    @Test
    fun `tryStart returns false while another backfill (any config) is still running`() {
        orchestrator.tryStart(configA)

        val startedB = orchestrator.tryStart(configB)

        assertThat(startedB).isFalse()
        verify(exactly = 1) { runner.run(any(), any()) }
        // status global segue apontando pra A — a config B nem entrou em execução.
        val status = orchestrator.globalStatus()
        assertThat(status.running).isTrue()
        assertThat(status.configId).isEqualTo(configA)
    }

    @Test
    fun `tryStart succeeds again after onFinished releases the flag`() {
        orchestrator.tryStart(configA)
        orchestrator.onFinished()

        assertThat(orchestrator.tryStart(configB)).isTrue()
        verify(exactly = 1) { runner.run(configB, orchestrator) }
    }

    @Test
    fun `global status counters accumulate onBatchProcessed`() {
        orchestrator.tryStart(configA)
        orchestrator.onBatchProcessed(BalanceBackfillBatchResult(processedCount = 10, deltaCount = 4))
        orchestrator.onBatchProcessed(BalanceBackfillBatchResult(processedCount = 5, deltaCount = 1))

        val status = orchestrator.globalStatus()
        assertThat(status.processedCount).isEqualTo(15L)
        assertThat(status.deltaCount).isEqualTo(5L)
    }

    @Test
    fun `global status keeps the last run counters after onFinished, running flips to false`() {
        orchestrator.tryStart(configA)
        orchestrator.onBatchProcessed(BalanceBackfillBatchResult(processedCount = 7, deltaCount = 2))
        orchestrator.onFinished()

        val status = orchestrator.globalStatus()
        assertThat(status.running).isFalse()
        assertThat(status.configId).isNull()
        assertThat(status.processedCount).isEqualTo(7L)
    }

    @Test
    fun `tryStart resets the counters for the new run`() {
        orchestrator.tryStart(configA)
        orchestrator.onBatchProcessed(BalanceBackfillBatchResult(processedCount = 9, deltaCount = 3))
        orchestrator.onFinished()

        orchestrator.tryStart(configB)

        val status = orchestrator.globalStatus()
        assertThat(status.configId).isEqualTo(configB)
        assertThat(status.processedCount).isEqualTo(0L)
        assertThat(status.deltaCount).isEqualTo(0L)
    }

    @Test
    fun `remainingCount and caughtUp come from countDecodedForBackfill for the focus config`() {
        every { events.countDecodedForBackfill(any(), any(), any(), any(), any(), any()) } returns 12L

        orchestrator.tryStart(configA)
        val status = orchestrator.globalStatus()

        assertThat(status.remainingCount).isEqualTo(12L)
        assertThat(status.caughtUp).isFalse()
    }

    @Test
    fun `global status is idle and caught up before any run`() {
        val status = orchestrator.globalStatus()

        assertThat(status.running).isFalse()
        assertThat(status.configId).isNull()
        assertThat(status.processedCount).isEqualTo(0L)
        assertThat(status.remainingCount).isEqualTo(0L)
        assertThat(status.caughtUp).isTrue()
    }

    @Test
    fun `publishes a global status snapshot on start, on each batch and on finish`() {
        orchestrator.tryStart(configA)
        orchestrator.onBatchProcessed(BalanceBackfillBatchResult(processedCount = 2, deltaCount = 2))
        orchestrator.onFinished()

        verify(exactly = 3) { statusPublisher.publish(any()) }
    }

    private fun sampleConfig(id: UUID) = BalanceMonitorConfig(
        id = id,
        contractAddress = "0xaaaa000000000000000000000000000000aaaa",
        network = Network.TESTNET,
        eventName = "Transfer",
        behavior = BalanceBehavior.TRANSFER,
        walletParam = null,
        fromParam = "from",
        toParam = "to",
        valueParam = "value",
        tokenIdParam = null,
        active = true,
        createdAt = Instant.now(),
        backfillWatermarkBlock = 100,
        backfillCursorBlock = -1,
        backfillCursorLogIndex = -1,
    )
}
