package br.com.bbchain.evex.indexer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.redecode.*` (`application.yml`) — tamanho do lote da reindexação assíncrona
 * (`ingestion-pipeline-reference.md` §4). Mesmo padrão `@Component`+`@ConfigurationProperties` de
 * [BlockchainProperties]/[AbiStorageProperties].
 */
@Component
@ConfigurationProperties(prefix = "indexer.redecode")
class RedecodeProperties {
    var batchSize: Int = 100
}
