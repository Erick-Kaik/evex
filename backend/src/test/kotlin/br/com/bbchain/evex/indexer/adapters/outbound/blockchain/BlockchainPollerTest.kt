package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.blockchain.RpcRangeException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import org.junit.jupiter.api.Test

/**
 * Unit test — [BlockchainPort]/[IngestionCursorPort]/[IngestionBatchProcessor] mockados via MockK
 * (DEC-009). Cenários replicados de `ingestion-pipeline-reference.md` §2 (auto-shrink).
 * [IngestionBatchProcessor] pode ser mockado apesar de ser uma classe concreta porque `@Component` já
 * é tornado `open` pelo plugin `kotlin("plugin.spring")` (all-open) — mesmo mecanismo que já vale para
 * qualquer bean Spring do projeto.
 */
class BlockchainPollerTest {

    private val blockchain: BlockchainPort = mockk()
    private val cursor: IngestionCursorPort = mockk()
    private val batchProcessor: IngestionBatchProcessor = mockk(relaxed = true)
    private val properties = BlockchainProperties().apply {
        confirmations = 0
        batchSize = 1000
        minBatchSize = 1
    }

    private val poller = BlockchainPoller(blockchain, cursor, batchProcessor, properties)

    private fun sampleLog(blockNumber: Long, logIndex: Long = 0) = BlockchainLog(
        contractAddress = "0x111111111111111111111111111111111111111a",
        blockNumber = blockNumber,
        blockHash = "0xblock$blockNumber",
        txHash = "0xtx$blockNumber-$logIndex",
        logIndex = logIndex,
        topics = listOf("0xtopic0"),
        data = "0x",
    )

    @Test
    fun `happy path - processes the whole window in a single call when getLogs succeeds`() {
        every { cursor.current() } returns 100
        every { blockchain.currentBlockNumber() } returns 200
        val logs = listOf(sampleLog(150))
        every { blockchain.getLogs(101, 200) } returns logs

        poller.poll()

        verify(exactly = 1) { batchProcessor.ingestRange(101, 200, logs) }
    }

    @Test
    fun `does nothing when PollWindow returns null (already caught up)`() {
        every { cursor.current() } returns 100
        every { blockchain.currentBlockNumber() } returns 100

        poller.poll()

        verify(exactly = 0) { blockchain.getLogs(any(), any()) }
        verify(exactly = 0) { batchProcessor.ingestRange(any(), any(), any()) }
    }

    @Test
    fun `auto-shrink - splits the window in half once when the node refuses the full range`() {
        every { cursor.current() } returns 100
        every { blockchain.currentBlockNumber() } returns 200 // window 101..200
        every { blockchain.getLogs(101, 200) } throws RpcRangeException("range too large")
        every { blockchain.getLogs(101, 150) } returns listOf(sampleLog(120))
        every { blockchain.getLogs(151, 200) } returns listOf(sampleLog(180))

        poller.poll()

        verify(exactly = 1) { batchProcessor.ingestRange(101, 150, listOf(sampleLog(120))) }
        verify(exactly = 1) { batchProcessor.ingestRange(151, 200, listOf(sampleLog(180))) }
    }

    @Test
    fun `auto-shrink - splits recursively across more than one level`() {
        every { cursor.current() } returns 0
        every { blockchain.currentBlockNumber() } returns 4 // window 1..4
        every { blockchain.getLogs(1, 4) } throws RpcRangeException("range too large")
        every { blockchain.getLogs(1, 2) } throws RpcRangeException("range too large")
        every { blockchain.getLogs(1, 1) } returns listOf(sampleLog(1))
        every { blockchain.getLogs(2, 2) } returns listOf(sampleLog(2))
        every { blockchain.getLogs(3, 4) } returns listOf(sampleLog(3))

        poller.poll()

        verify(exactly = 1) { batchProcessor.ingestRange(1, 1, listOf(sampleLog(1))) }
        verify(exactly = 1) { batchProcessor.ingestRange(2, 2, listOf(sampleLog(2))) }
        verify(exactly = 1) { batchProcessor.ingestRange(3, 4, listOf(sampleLog(3))) }
    }

    @Test
    fun `a failing single-block window does not shrink below minBatchSize, does not ingest, and does not crash the scheduler`() {
        every { cursor.current() } returns 0
        every { blockchain.currentBlockNumber() } returns 1 // window 1..1
        every { blockchain.getLogs(1, 1) } throws RpcRangeException("range too large")

        poller.poll() // must not throw

        verify(exactly = 1) { blockchain.getLogs(1, 1) }
        verify(exactly = 0) { batchProcessor.ingestRange(any(), any(), any()) }
    }

    @Test
    fun `a transport error (IOException) does not trigger auto-shrink - only one attempt`() {
        every { cursor.current() } returns 100
        every { blockchain.currentBlockNumber() } returns 300 // window 101..300
        every { blockchain.getLogs(101, 300) } throws IOException("connection refused")

        poller.poll() // must not throw, must not retry/split

        verify(exactly = 1) { blockchain.getLogs(101, 300) }
        verify(exactly = 0) { batchProcessor.ingestRange(any(), any(), any()) }
    }
}
