package br.com.bbchain.evex.indexer.domain.contract

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Instant
import java.util.UUID

/**
 * Contrato monitorado pelo Evex (doc de produto §4.1). `address` e `network` são imutáveis após a
 * criação (chave natural do contrato) — `update()` não os altera.
 *
 * `lineageId` (E29/DEC-096/DEC-097) agrupa "contratos redeployados" (endereço novo, sem proxy, mesma
 * linhagem lógica de produto) — aditivo, `null` para todo contrato cadastrado antes desta etapa (sem
 * backfill retroativo, não há como inferir a linhagem histórica). Todo contrato cadastrado a partir
 * desta etapa ganha um `lineageId` não nulo: o próprio `id` se não houver predecessor informado no
 * cadastro (linhagem trivial de tamanho 1), ou o `lineageId` herdado do predecessor.
 */
data class MonitoredContract(
    val id: UUID,
    val address: String,
    val name: String,
    val description: String?,
    val version: String?,
    val network: Network,
    val status: ContractStatus,
    val lineageId: UUID?,
    val createdAt: Instant,
)
