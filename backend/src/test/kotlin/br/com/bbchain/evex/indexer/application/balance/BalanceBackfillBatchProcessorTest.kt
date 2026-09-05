package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test — [BalanceMonitorConfigPort]/[BlockchainEventPort]/[BalanceProjectionService] mockados via MockK (DEC-009). */
class BalanceBackfillBatchProcessorTest {

    private val configs: BalanceMonitorConfigPort = mockk()
    private val events: BlockchainEventPort = mockk()
    private val projection: BalanceProjectionService = mockk()
    private val processor = BalanceBackfillBatchProcessor(configs, events, projection)

    private val configId = UUID.randomUUID()
    private val contractAddress = "0xaaaa000000000000000000000000000000aaaa"

    private fun config(
        watermark: Long = 1000,
        cursorBlock: Long = -1,
        cursorLogIndex: Long = -1,
    ) = BalanceMonitorConfig(
        id = configId,
        contractAddress = contractAddress,
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
        backfillWatermarkBlock = watermark,
        backfillCursorBlock = cursorBlock,
        backfillCursorLogIndex = cursorLogIndex,
    )

    private fun decodedEvent(blockNumber: Long, logIndex: Long) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = contractAddress,
        network = Network.TESTNET,
        blockNumber = blockNumber,
        blockHash = "0xblock$blockNumber",
        txHash = "0xtx$blockNumber$logIndex",
        logIndex = logIndex,
        topic0 = "0xtopic0",
        rawTopics = listOf("0xtopic0"),
        rawData = "0xdata",
        decoded = true,
        eventName = "Transfer",
        decodedParameters = mapOf("from" to "0x0", "to" to "0x1", "value" to "1"),
        blockTimestamp = Instant.now(),
        createdAt = Instant.now(),
    )

    @Test
    fun `processes a batch, projects only this config, and advances the cursor to the last row in the same call`() {
        val cfg = config(cursorBlock = -1, cursorLogIndex = -1)
        val page = listOf(decodedEvent(10, 0), decodedEvent(10, 2), decodedEvent(15, 1))
        every { configs.findById(configId) } returns cfg
        every {
            events.findDecodedForBackfill(contractAddress, Network.TESTNET, "Transfer", -1, -1, 1000, 500)
        } returns page
        every { projection.projectHistoryForConfig(page, cfg) } returns 3
        val savedSlot = slot<BalanceMonitorConfig>()
        every { configs.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = processor.processBatch(configId, 500)

        assertThat(result.processedCount).isEqualTo(3)
        assertThat(result.deltaCount).isEqualTo(3)
        verify(exactly = 1) { projection.projectHistoryForConfig(page, cfg) }
        assertThat(savedSlot.captured.backfillCursorBlock).isEqualTo(15L)
        assertThat(savedSlot.captured.backfillCursorLogIndex).isEqualTo(1L)
    }

    @Test
    fun `passes the persisted progress cursor and the watermark as the query bounds`() {
        val cfg = config(watermark = 800, cursorBlock = 42, cursorLogIndex = 7)
        every { configs.findById(configId) } returns cfg
        every {
            events.findDecodedForBackfill(contractAddress, Network.TESTNET, "Transfer", 42, 7, 800, 500)
        } returns emptyList()

        val result = processor.processBatch(configId, 500)

        assertThat(result.processedCount).isEqualTo(0)
        verify(exactly = 1) { events.findDecodedForBackfill(contractAddress, Network.TESTNET, "Transfer", 42, 7, 800, 500) }
    }

    @Test
    fun `empty batch returns processedCount 0, never projects, never advances the cursor`() {
        val cfg = config()
        every { configs.findById(configId) } returns cfg
        every { events.findDecodedForBackfill(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        val result = processor.processBatch(configId, 500)

        assertThat(result.processedCount).isEqualTo(0)
        assertThat(result.deltaCount).isEqualTo(0)
        verify(exactly = 0) { projection.projectHistoryForConfig(any(), any()) }
        verify(exactly = 0) { configs.save(any()) }
    }

    @Test
    fun `missing config is a no-op completed batch`() {
        every { configs.findById(configId) } returns null

        val result = processor.processBatch(configId, 500)

        assertThat(result.processedCount).isEqualTo(0)
        verify(exactly = 0) { events.findDecodedForBackfill(any(), any(), any(), any(), any(), any(), any()) }
    }
}
