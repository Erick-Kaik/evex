package br.com.bbchain.evex.indexer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.balance.*` (`application.yml`) — expressão cron do job noturno de consolidação de saldo
 * (doc §4.4, `BalanceSnapshotJob`). Mesmo padrão `@Component`+`@ConfigurationProperties` de
 * [RedecodeProperties]/[BlockchainProperties].
 */
@Component
@ConfigurationProperties(prefix = "indexer.balance")
class BalanceProperties {
    var snapshotCron: String = "0 0 0 * * *"
}
