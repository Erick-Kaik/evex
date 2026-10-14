package br.com.bbchain.evex.indexer.domain.event

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Instant

/**
 * Pré-filtro SQL da varredura de eventos que casam com um webhook: sempre `decoded = true` para
 * [contractAddress]/[eventName]/[network]; faixa por bloco OU por data (`blockTimestamp`) opcional. Os
 * `filters` do webhook (em memória) ficam fora da porta.
 */
data class EventScanFilter(
    val contractAddress: String,
    val eventName: String,
    val network: Network,
    val fromBlock: Long? = null,
    val toBlock: Long? = null,
    val fromDate: Instant? = null,
    val toDate: Instant? = null,
)
