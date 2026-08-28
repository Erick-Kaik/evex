package br.com.bbchain.evex.indexer.domain.balance

import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent

/**
 * Strategy de cálculo de saldo (`abi-strategy-design.md` §2) — 1 evento decodificado pode gerar 0, 1 ou
 * 2 [BalanceDelta]s (só `TransferBehavior` gera até 2, um por lado da transferência). Implementações em
 * `application/balance/` (não `domain/`: precisam de `@Component` do Spring pra serem injetadas como
 * `Map<BalanceBehavior, BalanceBehaviorStrategy>` em `BalanceProjectionService`, mesmo padrão de
 * wiring manual já usado por `AbiStorageResolver`, E3).
 *
 * Nunca lança exceção por parâmetro ausente/mal configurado — retorna lista vazia (config
 * `walletParam`/`valueParam`/etc. apontando pra um nome de parâmetro que não existe no evento
 * decodificado é um erro de configuração do usuário, não deve derrubar a transação de ingestão/redecode
 * em andamento, que projeta saldo pra **todos** os eventos do lote, não só o mal configurado).
 */
interface BalanceBehaviorStrategy {
    fun apply(config: BalanceMonitorConfig, event: BlockchainEvent): List<BalanceDelta>
}
