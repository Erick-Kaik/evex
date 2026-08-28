package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.application.balance.BalanceProjectionService
import br.com.bbchain.evex.indexer.application.webhook.WebhookMatchingService
import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.abi.AbiDecodingPort
import br.com.bbchain.evex.indexer.domain.abi.DecodedEvent
import br.com.bbchain.evex.indexer.domain.abi.RawLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit test — [BlockchainPort]/[AbiDecodingPort]/[BlockchainEventPort]/[IngestionCursorPort]
 * mockados via MockK (DEC-009). Cenários replicados de `ingestion-pipeline-reference.md` §3 e
 * `performance-and-ops-notes.md` (timestamp memoizado por lote).
 */
class IngestionBatchProcessorTest {

    private val blockchain: BlockchainPort = mockk()
    private val abiDecoding: AbiDecodingPort = mockk()
    private val events: BlockchainEventPort = mockk()
    private val cursor: IngestionCursorPort = mockk()
    private val indexerProperties = IndexerProperties().apply { network = Network.TESTNET }
    private val balanceProjectionService: BalanceProjectionService = mockk(relaxed = true)
    private val webhookMatchingService: WebhookMatchingService = mockk(relaxed = true)

    private val processor =
        IngestionBatchProcessor(blockchain, abiDecoding, events, cursor, indexerProperties, balanceProjectionService, webhookMatchingService)

    private fun sampleLog(blockNumber: Long, logIndex: Long, txHash: String) = BlockchainLog(
        contractAddress = "0x111111111111111111111111111111111111111a",
        blockNumber = blockNumber,
        blockHash = "0xblock$blockNumber",
        txHash = txHash,
        logIndex = logIndex,
        topics = listOf("0xtopic0"),
        data = "0xdata",
    )

    private fun stubDefaults() {
        every { blockchain.getBlockTimestamp(any()) } returns Instant.parse("2026-01-01T00:00:00Z")
        every { abiDecoding.decode(any(), any(), any()) } returns null
        every { events.saveAll(any()) } answers { firstArg() }
        every { cursor.advance(any()) } returns Unit
    }

    @Test
    fun `idempotency - skips a log that already exists, keeps the new one`() {
        stubDefaults()
        val existing = sampleLog(10, 0, "0xexisting")
        val fresh = sampleLog(10, 1, "0xfresh")
        every { events.existsByTxHashAndLogIndex("0xexisting", 0) } returns true
        every { events.existsByTxHashAndLogIndex("0xfresh", 1) } returns false
        val savedSlot = slot<List<BlockchainEvent>>()
        every { events.saveAll(capture(savedSlot)) } answers { savedSlot.captured }

        processor.ingestRange(10, 10, listOf(existing, fresh))

        assertThat(savedSlot.captured).hasSize(1)
        assertThat(savedSlot.captured.single().txHash).isEqualTo("0xfresh")
    }

    @Test
    fun `getBlockTimestamp is memoized once per distinct block, not once per event`() {
        stubDefaults()
        every { events.existsByTxHashAndLogIndex(any(), any()) } returns false
        val logs = listOf(
            sampleLog(10, 0, "0xa"),
            sampleLog(10, 1, "0xb"),
            sampleLog(11, 0, "0xc"),
        )

        processor.ingestRange(10, 11, logs)

        verify(exactly = 1) { blockchain.getBlockTimestamp(10) }
        verify(exactly = 1) { blockchain.getBlockTimestamp(11) }
    }

    @Test
    fun `a known topic0 decodes successfully - decoded is true with eventName and parameters`() {
        stubDefaults()
        every { events.existsByTxHashAndLogIndex(any(), any()) } returns false
        val log = sampleLog(10, 0, "0xa")
        every {
            abiDecoding.decode(log.contractAddress, log.blockNumber, RawLog(topics = log.topics, data = log.data))
        } returns DecodedEvent(eventName = "Transfer", parameters = mapOf("value" to "100"))
        val savedSlot = slot<List<BlockchainEvent>>()
        every { events.saveAll(capture(savedSlot)) } answers { savedSlot.captured }

        processor.ingestRange(10, 10, listOf(log))

        val saved = savedSlot.captured.single()
        assertThat(saved.decoded).isTrue()
        assertThat(saved.eventName).isEqualTo("Transfer")
        assertThat(saved.decodedParameters).isEqualTo(mapOf("value" to "100"))
    }

    @Test
    fun `an unknown topic0 is stored raw - decoded is false, no eventName or parameters`() {
        stubDefaults()
        every { events.existsByTxHashAndLogIndex(any(), any()) } returns false
        val log = sampleLog(10, 0, "0xa")
        val savedSlot = slot<List<BlockchainEvent>>()
        every { events.saveAll(capture(savedSlot)) } answers { savedSlot.captured }

        processor.ingestRange(10, 10, listOf(log))

        val saved = savedSlot.captured.single()
        assertThat(saved.decoded).isFalse()
        assertThat(saved.eventName).isNull()
        assertThat(saved.decodedParameters).isNull()
        assertThat(saved.rawTopics).isEqualTo(log.topics)
        assertThat(saved.rawData).isEqualTo(log.data)
    }

    @Test
    fun `the cursor advances only after the window's events are saved`() {
        stubDefaults()
        every { events.existsByTxHashAndLogIndex(any(), any()) } returns false

        processor.ingestRange(10, 20, listOf(sampleLog(10, 0, "0xa")))

        verifyOrder {
            events.saveAll(any())
            cursor.advance(20)
        }
    }

    @Test
    fun `the cursor still advances even when the window has no logs`() {
        stubDefaults()

        processor.ingestRange(30, 40, emptyList())

        verify(exactly = 0) { events.saveAll(any()) }
        verify(exactly = 1) { cursor.advance(40) }
        verify(exactly = 0) { balanceProjectionService.projectBatch(any()) }
    }

    @Test
    fun `the cursor still advances when every log in the window was already ingested`() {
        stubDefaults()
        every { events.existsByTxHashAndLogIndex(any(), any()) } returns true

        processor.ingestRange(30, 40, listOf(sampleLog(35, 0, "0xdup")))

        verify(exactly = 0) { events.saveAll(any()) }
        verify(exactly = 1) { cursor.advance(40) }
        verify(exactly = 0) { balanceProjectionService.projectBatch(any()) }
    }

    @Test
    fun `newly saved events are projected for balance, in the same batch`() {
        stubDefaults()
        every { events.existsByTxHashAndLogIndex(any(), any()) } returns false
        val savedSlot = slot<List<BlockchainEvent>>()
        every { events.saveAll(capture(savedSlot)) } answers { savedSlot.captured }

        processor.ingestRange(10, 10, listOf(sampleLog(10, 0, "0xa")))

        verify(exactly = 1) { balanceProjectionService.projectBatch(savedSlot.captured) }
        verify(exactly = 1) { webhookMatchingService.matchAndEnqueue(savedSlot.captured) }
    }

    @Test
    fun `saved events carry the network from IndexerProperties, not from any contract`() {
        stubDefaults()
        every { events.existsByTxHashAndLogIndex(any(), any()) } returns false
        val savedSlot = slot<List<BlockchainEvent>>()
        every { events.saveAll(capture(savedSlot)) } answers { savedSlot.captured }

        processor.ingestRange(10, 10, listOf(sampleLog(10, 0, "0xa")))

        assertThat(savedSlot.captured.single().network).isEqualTo(Network.TESTNET)
    }
}
