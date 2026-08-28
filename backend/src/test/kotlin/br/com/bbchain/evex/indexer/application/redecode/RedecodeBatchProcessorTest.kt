package br.com.bbchain.evex.indexer.application.redecode

import br.com.bbchain.evex.indexer.application.balance.BalanceProjectionService
import br.com.bbchain.evex.indexer.application.webhook.WebhookMatchingService
import br.com.bbchain.evex.indexer.domain.abi.AbiDecodingPort
import br.com.bbchain.evex.indexer.domain.abi.DecodedEvent
import br.com.bbchain.evex.indexer.domain.abi.RawLog
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test — [BlockchainEventPort]/[AbiDecodingPort] mockados via MockK (DEC-009). */
class RedecodeBatchProcessorTest {

    private val events: BlockchainEventPort = mockk()
    private val abiDecoding: AbiDecodingPort = mockk()
    private val balanceProjectionService: BalanceProjectionService = mockk(relaxed = true)
    private val webhookMatchingService: WebhookMatchingService = mockk(relaxed = true)
    private val processor = RedecodeBatchProcessor(events, abiDecoding, balanceProjectionService, webhookMatchingService)

    private fun rawEvent(
        address: String = "0xaaaa000000000000000000000000000000aaaa",
        topic0: String = "0xtopic0",
        blockNumber: Long = 10,
    ) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = Network.TESTNET,
        blockNumber = blockNumber,
        blockHash = "0xblock",
        txHash = "0xtx",
        logIndex = 0,
        topic0 = topic0,
        rawTopics = listOf(topic0),
        rawData = "0xdata",
        decoded = false,
        eventName = null,
        decodedParameters = null,
        blockTimestamp = Instant.now(),
        createdAt = Instant.now(),
    )

    @Test
    fun `decodes a row with a known topic0 and sends it to updateAll`() {
        val event = rawEvent()
        val filter = RedecodeFilter()
        every { events.findPendingCandidates(filter, 100) } returns listOf(event)
        every {
            abiDecoding.decode(event.contractAddress, event.blockNumber, RawLog(topics = event.rawTopics, data = event.rawData))
        } returns DecodedEvent(eventName = "Transfer", parameters = mapOf("value" to "1"))
        val updatedSlot = slot<List<BlockchainEvent>>()
        every { events.updateAll(capture(updatedSlot)) } answers { updatedSlot.captured }

        val result = processor.processBatch(filter, 100)

        assertThat(result.processedCount).isEqualTo(1)
        assertThat(result.decodedCount).isEqualTo(1)
        assertThat(updatedSlot.captured).hasSize(1)
        val updated = updatedSlot.captured.single()
        assertThat(updated.decoded).isTrue()
        assertThat(updated.eventName).isEqualTo("Transfer")
        assertThat(updated.decodedParameters).isEqualTo(mapOf("value" to "1"))
        verify(exactly = 1) { events.updateAll(any()) }
        verify(exactly = 1) { balanceProjectionService.projectBatch(updatedSlot.captured) }
        verify(exactly = 1) { webhookMatchingService.matchAndEnqueue(updatedSlot.captured) }
    }

    @Test
    fun `keeps decoded=false and does not call updateAll when topic0 is still unknown`() {
        val event = rawEvent()
        val filter = RedecodeFilter()
        every { events.findPendingCandidates(filter, 100) } returns listOf(event)
        every {
            abiDecoding.decode(event.contractAddress, event.blockNumber, RawLog(topics = event.rawTopics, data = event.rawData))
        } returns null

        val result = processor.processBatch(filter, 100)

        assertThat(result.processedCount).isEqualTo(1)
        assertThat(result.decodedCount).isEqualTo(0)
        verify(exactly = 0) { events.updateAll(any()) }
        verify(exactly = 0) { balanceProjectionService.projectBatch(any()) }
        verify(exactly = 0) { webhookMatchingService.matchAndEnqueue(any()) }
    }

    @Test
    fun `empty batch returns processedCount 0 and never calls updateAll`() {
        val filter = RedecodeFilter()
        every { events.findPendingCandidates(filter, 100) } returns emptyList()

        val result = processor.processBatch(filter, 100)

        assertThat(result.processedCount).isEqualTo(0)
        assertThat(result.decodedCount).isEqualTo(0)
        verify(exactly = 0) { events.updateAll(any()) }
        verify(exactly = 0) { abiDecoding.decode(any(), any(), any()) }
        verify(exactly = 0) { balanceProjectionService.projectBatch(any()) }
    }

    @Test
    fun `updateAll is called only with the rows that changed, not the whole batch`() {
        val decodable = rawEvent(topic0 = "0xknown")
        val undecodable = rawEvent(topic0 = "0xunknown")
        val filter = RedecodeFilter()
        every { events.findPendingCandidates(filter, 100) } returns listOf(decodable, undecodable)
        every {
            abiDecoding.decode(decodable.contractAddress, decodable.blockNumber, RawLog(topics = decodable.rawTopics, data = decodable.rawData))
        } returns DecodedEvent(eventName = "Transfer", parameters = emptyMap())
        every {
            abiDecoding.decode(undecodable.contractAddress, undecodable.blockNumber, RawLog(topics = undecodable.rawTopics, data = undecodable.rawData))
        } returns null
        val updatedSlot = slot<List<BlockchainEvent>>()
        every { events.updateAll(capture(updatedSlot)) } answers { updatedSlot.captured }

        val result = processor.processBatch(filter, 100)

        assertThat(result.processedCount).isEqualTo(2)
        assertThat(result.decodedCount).isEqualTo(1)
        assertThat(updatedSlot.captured).hasSize(1)
        assertThat(updatedSlot.captured.single().id).isEqualTo(decodable.id)
    }
}
