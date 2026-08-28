package br.com.bbchain.evex.indexer.domain.event

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Instant
import java.util.UUID

/**
 * Um log da rede EVM ingerido pelo poller — raw e decodificado unificados na mesma entidade (regra
 * "nenhum evento é descartado": contrato sem ABI cadastrada grava `decoded=false` com
 * `rawTopics`/`rawData` preservados, permitindo reindexação posterior sem reconsultar a rede).
 *
 * `network` vem de uma config **global da instância** (`indexer.network`), não do
 * [br.com.bbchain.evex.indexer.domain.contract.MonitoredContract] — a ingestão é da rede inteira
 * (DEC-001), então a maioria dos eventos é de endereços não cadastrados. Decisão tomada com o usuário
 * na sessão de planejamento da E4, consistente com "instância por rede" (`CLAUDE.md` raiz).
 */
data class BlockchainEvent(
    val id: UUID,
    val contractAddress: String,
    val network: Network,
    val blockNumber: Long,
    val blockHash: String,
    val txHash: String,
    val logIndex: Long,
    val topic0: String?,
    val rawTopics: List<String>,
    val rawData: String,
    val decoded: Boolean,
    val eventName: String?,
    val decodedParameters: Map<String, Any?>?,
    val blockTimestamp: Instant?,
    val createdAt: Instant,
)
