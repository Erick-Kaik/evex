package br.com.bbchain.evex.indexer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.balance.*` (`application.yml`) — expressão cron do job noturno de consolidação de saldo
 * (doc §4.4, `BalanceSnapshotJob`) e tamanho do lote do backfill de saldo sob demanda (E14, DEC-066,
 * espelha `RedecodeProperties.batchSize`). Mesmo padrão `@Component`+`@ConfigurationProperties` de
 * [RedecodeProperties]/[BlockchainProperties].
 */
@Component
@ConfigurationProperties(prefix = "indexer.balance")
class BalanceProperties {
    var snapshotCron: String = "0 0 0 * * *"
    var backfillBatchSize: Int = 500
}
