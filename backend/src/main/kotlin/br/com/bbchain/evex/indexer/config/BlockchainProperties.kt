package br.com.bbchain.evex.indexer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.blockchain.*` (`application.yml`) — conexão com o nó, janela do poller e auto-shrink
 * (`ingestion-pipeline-reference.md` §1/§2). Mesmo padrão de [AbiStorageProperties] (`@Component`,
 * sem `@EnableConfigurationProperties` separado — pego pelo component scan padrão da raiz do pacote).
 */
@Component
@ConfigurationProperties(prefix = "indexer.blockchain")
class BlockchainProperties {
    /** URL do `HttpService` do Web3j — sem BBChain Keeper (regra não-negociável). */
    var rpcUrl: String = "http://localhost:8545"

    /** Quantos blocos de "cauda" ficam de fora do `safeHead` por segurança de reorg (DEC-005). */
    var confirmations: Long = 0

    /** Tamanho máximo (em blocos) da janela processada por tick. */
    var batchSize: Long = 1000

    /** Piso do auto-shrink — abaixo disso, erro de range sobe cru em vez de dividir mais. */
    var minBatchSize: Long = 1

    /** Intervalo entre ticks do `@Scheduled`. */
    var pollIntervalMs: Long = 5000

    /**
     * `false` em testes — setado via `@DynamicPropertySource` em `AbstractIntegrationTest`, nunca em
     * `src/test/resources/application.yml` (DEC-024: esse arquivo faz shadow do
     * `src/main/resources/application.yml` no classpath de teste). Evita o `@Scheduled` batendo em RPC
     * inexistente durante `@SpringBootTest`.
     */
    var pollingEnabled: Boolean = true
}
