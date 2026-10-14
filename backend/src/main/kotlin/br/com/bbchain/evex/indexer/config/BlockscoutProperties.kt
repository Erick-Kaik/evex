package br.com.bbchain.evex.indexer.config

import java.time.Duration
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

    /** Liga o uso dos logs `Upgraded` da rede inteira (DEC-107; consumido pela camada de aplicação). */
    var upgradeLogsEnabled: Boolean = true

    /** TTL do cache em memória de `getAllUpgradeLogs`; zero (ou negativo) desliga o cache. */
    var upgradeLogsCacheTtl: Duration = Duration.ofSeconds(30)

    /** Teto de páginas (por bloco) de `getAllUpgradeLogs`; atingi-lo marca o resultado como truncado. */
    var upgradeLogsMaxPages: Int = 10

    /** Timeout de conexão do RestClient do Blockscout (vale para todos os endpoints). */
    var connectTimeout: Duration = Duration.ofSeconds(2)

    /** Timeout de leitura do RestClient (vale para todos os endpoints; folga para corpo de ~1 MB). */
    var readTimeout: Duration = Duration.ofSeconds(15)
}
