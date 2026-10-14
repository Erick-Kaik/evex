package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockStrategies
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockStrategy
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registra o `modo -> estratégia` de bloco do enriquecimento: as embutidas
 * ([EnrichmentBlockStrategies.builtIn]) mais qualquer bean [EnrichmentBlockStrategy] adicional — modo
 * novo = bean novo, sem tocar o resolver nem espalhar `when`.
 */
@Configuration
class EnrichmentBlockConfig {
    @Bean
    fun enrichmentBlockStrategies(extra: ObjectProvider<EnrichmentBlockStrategy>): EnrichmentBlockStrategies =
        EnrichmentBlockStrategies(EnrichmentBlockStrategies.builtIn() + extra.orderedStream().toList())
}
