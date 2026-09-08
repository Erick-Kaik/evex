package br.com.bbchain.evex.indexer.config

/**
 * `indexer.blockchain.transport` — qual gatilho dispara [br.com.bbchain.evex.indexer.adapters.outbound
 * .blockchain.PollTickService.tick] (a lógica de ingestão em si — janela/auto-shrink/`getLogs` — é
 * **a mesma** independente do transporte, ver DEC-091). Não é sobre o transporte usado por
 * [br.com.bbchain.evex.indexer.adapters.outbound.blockchain.Web3jBlockchainClient] (esse continua
 * 100% HTTP pro `getLogs`/`call`/`hasCode`/etc., nunca muda com esta config).
 */
enum class BlockchainTransport {
    /** Default — `@Scheduled` de intervalo fixo ([indexer.blockchain.pollIntervalMs]), padrão já validado (E4/E12). */
    POLLING,

    /** `eth_subscribe("newHeads")` via uma 2ª instância de `Web3j` só pra isso (DEC-003 revisitada, DEC-091). */
    WEBSOCKET,

    /**
     * Nenhum gatilho ativo — nem `@Scheduled` nem WebSocket. Usado por `indexer.blockchain.transport`
     * em testes de integração (`AbstractIntegrationTest`) pra desligar a ingestão inteira contra um RPC
     * inexistente, sem sobrecarregar o nome `transport` com um significado "desligado" escondido atrás
     * de `POLLING`/`WEBSOCKET`. Ver DEC-091 para o racional completo (substitui a antiga property
     * `polling-enabled`).
     */
    NONE,
}
