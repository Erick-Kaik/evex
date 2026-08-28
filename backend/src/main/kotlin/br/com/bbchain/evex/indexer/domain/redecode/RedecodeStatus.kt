package br.com.bbchain.evex.indexer.domain.redecode

/**
 * Snapshot do estado da reindexação assíncrona (`GET /v1/redecode/status`), lido a partir dos
 * contadores atômicos do `RedecodeOrchestrator` — `remainingCount` é a única parte consultada ao vivo
 * (`BlockchainEventPort.countPending`), o resto vem de memória.
 */
data class RedecodeStatus(
    val running: Boolean,
    val processedCount: Long,
    val decodedCount: Long,
    val remainingCount: Long,
)
