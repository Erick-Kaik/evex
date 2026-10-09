package br.com.bbchain.evex.indexer.config

import br.com.bbchain.evex.indexer.domain.model.CorrelationKeyResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Expõe o [CorrelationKeyResolver] (domínio puro) como bean único usado pela projeção, webhook e validação (DEC-111). */
@Configuration
class CorrelationKeyConfig {
    @Bean
    fun correlationKeyResolver(): CorrelationKeyResolver = CorrelationKeyResolver()
}
