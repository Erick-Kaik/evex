package br.com.bbchain.evex.indexer.application.redecode

import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.jobstatus.JobStatusPublisherPort
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit test — [RedecodeRunner] mockado via MockK (sem `@Async` real, é só chamada de método mockada;
 * cobertura de "loop até não progredir" fica pro teste de integração ponta a ponta).
 */
class RedecodeOrchestratorTest {

    private val runner: RedecodeRunner = mockk(relaxed = true)
    private val events: BlockchainEventPort = mockk()
    private val statusPublisher: JobStatusPublisherPort<RedecodeStatus> = mockk(relaxed = true)
    private lateinit var orchestrator: RedecodeOrchestrator

    @BeforeEach
    fun setUp() {
        orchestrator = RedecodeOrchestrator(runner, events, statusPublisher)
        every { events.countPending(any()) } returns 0L
    }

    @Test
    fun `publishes a status snapshot on start, on each batch and on finish`() {
        orchestrator.tryStart(RedecodeFilter())
        orchestrator.onBatchProcessed(RedecodeBatchResult(processedCount = 3, decodedCount = 1))
        orchestrator.onFinished()

        verify(exactly = 3) { statusPublisher.publish(any()) }
    }

    @Test
    fun `tryStart returns true the first time and dispatches the runner`() {
        val filter = RedecodeFilter(contractAddress = "0xaaaa000000000000000000000000000000aaaa")

        val started = orchestrator.tryStart(filter)

        assertThat(started).isTrue()
        verify(exactly = 1) { runner.run(filter, orchestrator) }
    }

    @Test
    fun `tryStart returns false on a second call while still running`() {
        val filter = RedecodeFilter()
        orchestrator.tryStart(filter) // primeira chamada: running vira true; nunca chama onFinished()
        // porque runner é mockado (relaxed) e não invoca de fato o loop/orchestrator.onFinished().

        val startedAgain = orchestrator.tryStart(filter)

        assertThat(startedAgain).isFalse()
        verify(exactly = 1) { runner.run(any(), any()) }
    }

    @Test
    fun `tryStart succeeds again after onFinished releases the flag`() {
        val filter = RedecodeFilter()
        orchestrator.tryStart(filter)
        orchestrator.onFinished()

        val startedAgain = orchestrator.tryStart(filter)

        assertThat(startedAgain).isTrue()
        verify(exactly = 2) { runner.run(filter, orchestrator) }
    }

    @Test
    fun `status reflects counters after onBatchProcessed`() {
        every { events.countPending(any()) } returns 7L

        orchestrator.onBatchProcessed(RedecodeBatchResult(processedCount = 10, decodedCount = 4))
        orchestrator.onBatchProcessed(RedecodeBatchResult(processedCount = 5, decodedCount = 0))

        val status = orchestrator.status()

        assertThat(status.processedCount).isEqualTo(15L)
        assertThat(status.decodedCount).isEqualTo(4L)
        assertThat(status.remainingCount).isEqualTo(7L)
    }

    @Test
    fun `status running flag reflects whether an execution is in progress`() {
        assertThat(orchestrator.status().running).isFalse()

        orchestrator.tryStart(RedecodeFilter())
        assertThat(orchestrator.status().running).isTrue()

        orchestrator.onFinished()
        assertThat(orchestrator.status().running).isFalse()
    }

    @Test
    fun `remainingCount comes from countPending using the filter of the last tryStart`() {
        val filter = RedecodeFilter(topic0 = "0xtopic0")
        every { events.countPending(filter) } returns 42L

        orchestrator.tryStart(filter)
        val status = orchestrator.status()

        assertThat(status.remainingCount).isEqualTo(42L)
        verify { events.countPending(filter) }
    }
}
