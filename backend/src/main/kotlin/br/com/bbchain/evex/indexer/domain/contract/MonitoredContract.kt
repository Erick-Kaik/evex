package br.com.bbchain.evex.indexer.domain.contract

import br.com.bbchain.evex.indexer.domain.common.Network
import java.util.UUID

/**
 * Contrato monitorado pelo Evex (doc de produto §4.1). `address` e `network` são imutáveis após a
 * criação (chave natural do contrato) — `update()` não os altera.
 */
data class MonitoredContract(
    val id: UUID,
    val address: String,
    val name: String,
    val description: String?,
    val version: String?,
    val network: Network,
    val status: ContractStatus,
)
