package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.adapters.outbound.abi.BuiltinInterfaceTemplates
import br.com.bbchain.evex.indexer.domain.abi.DetectionLevel
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit test — [BlockchainEventPort]/[BlockscoutPort] mockados via MockK (DEC-009). */
class InterfaceDetectionServiceTest {

    private val blockchainEvents: BlockchainEventPort = mockk()
    private val blockscout: BlockscoutPort = mockk()
    // Real: só monta os 3 templates built-in parseando ABI JSON, não vale a pena mockar.
    private val templates = BuiltinInterfaceTemplates(br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser())

    private lateinit var service: InterfaceDetectionService

    private val address = "0xaaaa000000000000000000000000000000aaaa"
    private val transferTopic0 = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
    private val approvalTopic0 = "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925"

    @BeforeEach
    fun setUp() {
        service = InterfaceDetectionService(blockchainEvents, blockscout, templates)
    }

    @Test
    fun `uses BlockchainEventPort when it already has observed topic0s, never touches Blockscout`() {
        every { blockchainEvents.findDistinctTopic0s(address) } returns setOf(transferTopic0, approvalTopic0)

        val result = service.suggest(address)

        assertThat(result).anyMatch { it.templateName == "ERC20" && it.level == DetectionLevel.FULL }
        verify(exactly = 0) { blockscout.getLogTopic0s(any()) }
    }

    @Test
    fun `falls back to BlockscoutPort when BlockchainEventPort has nothing observed yet`() {
        every { blockchainEvents.findDistinctTopic0s(address) } returns emptySet()
        every { blockscout.getLogTopic0s(address) } returns setOf(transferTopic0, approvalTopic0)

        val result = service.suggest(address)

        assertThat(result).anyMatch { it.templateName == "ERC20" && it.level == DetectionLevel.FULL }
        verify { blockscout.getLogTopic0s(address) }
    }

    @Test
    fun `swallows BlockscoutUnavailableException from the fallback, returns an empty list instead of propagating`() {
        every { blockchainEvents.findDistinctTopic0s(address) } returns emptySet()
        every { blockscout.getLogTopic0s(address) } throws BlockscoutUnavailableException("not configured")

        val result = service.suggest(address)

        assertThat(result).isEmpty()
    }

    @Test
    fun `filters out templates with no match at all`() {
        every { blockchainEvents.findDistinctTopic0s(address) } returns setOf("0xsomeCustomEventTopic0")

        val result = service.suggest(address)

        assertThat(result).isEmpty()
        verify(exactly = 0) { blockscout.getLogTopic0s(any()) }
    }
}
