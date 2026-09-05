package br.com.bbchain.evex.indexer.domain.balance

import java.util.UUID

/**
 * Snapshot do estado do backfill de saldo sob demanda de uma [BalanceMonitorConfig] (E14, DEC-066) —
 * lido a partir dos contadores atômicos do `BalanceBackfillOrchestrator`; `remainingCount` é a única
 * parte consultada ao vivo (`BlockchainEventPort.countDecodedForBackfill`), o resto vem de memória.
 *
 * Análogo a [br.com.bbchain.evex.indexer.domain.redecode.RedecodeStatus], mas por configuração — o
 * job é único e global (1 backfill por vez em toda a instância), então [configId] carrega qual config
 * está com o backfill em andamento agora (`null` quando nenhum está rodando), permitindo à UI
 * distinguir "o backfill desta config" de "há um backfill de outra config em andamento".
 */
data class BalanceBackfillStatus(
    val running: Boolean,
    val configId: UUID?,
    val processedCount: Long,
    val deltaCount: Long,
    val remainingCount: Long,
    val caughtUp: Boolean,
)
