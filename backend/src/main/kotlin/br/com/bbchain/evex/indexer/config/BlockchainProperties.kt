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

    /** Intervalo entre ticks do `@Scheduled` (só usado quando [transport] é [BlockchainTransport.POLLING]). */
    var pollIntervalMs: Long = 5000

    /**
     * Qual gatilho dispara a ingestão — `POLLING` (default, `@Scheduled` de intervalo fixo, E4/E12) ou
     * `WEBSOCKET` (`eth_subscribe("newHeads")`, E26/DEC-091). `NONE` desliga os dois — usado só em
     * teste (`AbstractIntegrationTest`), substitui a antiga property `polling-enabled` (DEC-091).
     * Default `POLLING` preserva 100% o comportamento anterior a esta property existir.
     */
    var transport: BlockchainTransport = BlockchainTransport.POLLING

    /**
     * URL do `WebSocketService` do Web3j — só usada quando [transport] é [BlockchainTransport.WEBSOCKET].
     * Instância de `Web3j` **separada** da usada por [br.com.bbchain.evex.indexer.adapters.outbound
     * .blockchain.Web3jBlockchainClient] (essa continua 100% HTTP, DEC-091) — só pra assinar
     * `newHeadsNotifications()` e disparar o mesmo `PollTickService.tick()` do polling.
     */
    var wsUrl: String = "ws://localhost:8546"
}
