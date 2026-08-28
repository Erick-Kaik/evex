package br.com.bbchain.evex.indexer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.blockscout.*` (`application.yml`) — `base-url` vazio (default) desabilita o cliente
 * Blockscout por completo: `BlockscoutClient` lança `BlockscoutUnavailableException` direto, sem
 * tentar HTTP nenhum (Blockscout nunca é dependência crítica do caminho principal). Mesmo padrão
 * `@Component`+`@ConfigurationProperties` de [AbiStorageProperties]/[BlockchainProperties].
 */
@Component
@ConfigurationProperties(prefix = "indexer.blockscout")
class BlockscoutProperties {
    var baseUrl: String = ""
}
