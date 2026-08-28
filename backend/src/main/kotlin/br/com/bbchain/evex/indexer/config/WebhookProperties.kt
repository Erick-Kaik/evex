package br.com.bbchain.evex.indexer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.webhook.*` (`application.yml`) — job de dispatch (E8, doc §4.6). Mesmo padrão
 * `@Component`+`@ConfigurationProperties` de [RedecodeProperties]/[BalanceProperties].
 */
@Component
@ConfigurationProperties(prefix = "indexer.webhook")
class WebhookProperties {
    var dispatchIntervalMs: Long = 5000
    var dispatchBatchSize: Int = 50
    var maxAttempts: Int = 5
    var baseDelaySeconds: Long = 10
    var maxDelaySeconds: Long = 300
    var requestTimeoutMs: Int = 5000
}
