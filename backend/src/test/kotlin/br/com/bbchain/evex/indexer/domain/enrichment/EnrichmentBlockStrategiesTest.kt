package br.com.bbchain.evex.indexer.domain.enrichment

import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EnrichmentBlockStrategiesTest {

    private val strategies = EnrichmentBlockStrategies.default()

    @Test
    fun `built-in modes map to the expected CallBlock`() {
        assertThat(strategies.resolve(EnrichmentBlockSpec.EventBlock, 42)).isEqualTo(CallBlock.Number(42))
        assertThat(strategies.resolve(EnrichmentBlockSpec(EnrichmentBlockSpec.LATEST), 42)).isEqualTo(CallBlock.Latest)
        assertThat(strategies.resolve(EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_NUMBER, 7), 42)).isEqualTo(CallBlock.Number(7))
        assertThat(strategies.resolve(EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_AT_DATE, 9), 42)).isEqualTo(CallBlock.Number(9))
    }

    @Test
    fun `EVENT_BLOCK without event block is null (reported per call), not an exception`() {
        assertThat(strategies.resolve(EnrichmentBlockSpec.EventBlock, null)).isNull()
    }

    @Test
    fun `BLOCK_AT_DATE not resolved yet is a clear error`() {
        assertThatThrownBy { strategies.resolve(EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_AT_DATE), 42) }
            .isInstanceOf(InvalidEnrichmentBlockSpecException::class.java)
            .hasMessageContaining("BLOCK_AT_DATE")
    }

    @Test
    fun `unknown mode lists supported ones, and a new mode is just a new strategy`() {
        assertThatThrownBy { strategies.resolve(EnrichmentBlockSpec("X"), 1) }.isInstanceOf(UnknownEnrichmentBlockModeException::class.java)

        val extended = EnrichmentBlockStrategies(
            EnrichmentBlockStrategies.builtIn() + object : EnrichmentBlockStrategy {
                override val mode = "EVENT_BLOCK_MINUS_ONE"
                override fun resolve(eventBlockNumber: Long?, spec: EnrichmentBlockSpec) = eventBlockNumber?.let { CallBlock.Number(it - 1) }
            },
        )
        assertThat(extended.resolve(EnrichmentBlockSpec("EVENT_BLOCK_MINUS_ONE"), 10)).isEqualTo(CallBlock.Number(9))
    }

    @Test
    fun `negative block numbers are rejected`() {
        assertThatThrownBy { strategies.resolve(EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_NUMBER, -1), 1) }
            .isInstanceOf(InvalidEnrichmentBlockSpecException::class.java)
    }
}
