package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.application.block.BlockResolutionService
import br.com.bbchain.evex.indexer.config.BlockResolutionProperties
import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiMethodCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockStrategies
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockStrategy
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.function.Supplier
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * Prova (sem Docker/DB) que o wiring Spring das novas classes funciona com parâmetros Kotlin com default
 * e dependência opcional: o resolver sobe só com as 6 portas antigas (+ `EnrichmentBlockStrategies` do
 * `@Configuration`), e `BlockResolutionService` sobe SEM bean de `BlockTimestampLookupPort` (a
 * implementação JPA só chega na fatia 2).
 */
class EnrichmentWiringTest {

    private val noCustomizers = emptyArray<BeanDefinitionCustomizer>()

    @Test
    fun `resolver and block resolution service are wired by Spring, extra strategy beans extend the modes`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ctx.registerBean(EnrichmentProfilePort::class.java, Supplier { mockk<EnrichmentProfilePort>() })
            ctx.registerBean(MonitoredContractPort::class.java, Supplier { mockk<MonitoredContractPort>() })
            ctx.registerBean(ContractAbiPort::class.java, Supplier { mockk<ContractAbiPort>() })
            ctx.registerBean(AbiContentReaderPort::class.java, Supplier { mockk<AbiContentReaderPort>() })
            ctx.registerBean(AbiMethodCatalogPort::class.java, Supplier { mockk<AbiMethodCatalogPort>() })
            ctx.registerBean(BlockchainPort::class.java, Supplier { mockk<BlockchainPort>() })
            ctx.registerBean(IndexerProperties::class.java, Supplier { IndexerProperties() })
            ctx.registerBean(BlockResolutionProperties::class.java, Supplier { BlockResolutionProperties() })
            ctx.registerBean(EnrichmentBlockConfig::class.java, *noCustomizers)
            ctx.registerBean(
                "extraStrategy",
                EnrichmentBlockStrategy::class.java,
                Supplier {
                    object : EnrichmentBlockStrategy {
                        override val mode = "CUSTOM"
                        override fun resolve(eventBlockNumber: Long?, spec: EnrichmentBlockSpec) = CallBlock.Latest
                    }
                },
            )
            ctx.registerBean(WebhookEnrichmentResolver::class.java, *noCustomizers)
            ctx.registerBean(BlockResolutionService::class.java, *noCustomizers)
            ctx.refresh()

            assertThat(ctx.getBean(WebhookEnrichmentResolver::class.java)).isNotNull()
            assertThat(ctx.getBean(BlockResolutionService::class.java)).isNotNull()
            assertThat(ctx.getBean(EnrichmentBlockStrategies::class.java).supportedModes)
                .containsExactlyInAnyOrder("EVENT_BLOCK", "LATEST", "BLOCK_NUMBER", "BLOCK_AT_DATE", "CUSTOM")
        }
    }
}
