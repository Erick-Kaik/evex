package br.com.bbchain.evex.indexer.domain.event

import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter

/**
 * Porta de saída para persistência/consulta de eventos indexados. Implementação JPA/Postgres em
 * `adapters/outbound/persistence/JpaBlockchainEventAdapter`.
 */
interface BlockchainEventPort {
    /** Idempotência por `(tx_hash, log_index)` — `ingestion-pipeline-reference.md` §3. */
    fun existsByTxHashAndLogIndex(txHash: String, logIndex: Long): Boolean

    /** Escrita em lote de linhas **novas** (`INSERT`) — nunca `save()` 1-a-1. Usado pela ingestão (E4). */
    fun saveAll(events: List<BlockchainEvent>): List<BlockchainEvent>

    /**
     * Escrita em lote de linhas **já existentes** (`UPDATE`) — usado pelo redecode (E5): as linhas vêm
     * de [findPendingCandidates], já têm PK no banco; chamar [saveAll] nelas tentaria um `INSERT` sobre
     * PK existente. Ver `docs/decisions.md` (achado da E5 sobre `Persistable.isNew()`).
     */
    fun updateAll(events: List<BlockchainEvent>): List<BlockchainEvent>

    fun search(filter: EventSearchFilter, page: Int, size: Int): PageResult<BlockchainEvent>

    /**
     * Candidatos a redecode: `decoded=false` + [filter] (todos os campos opcionais). Ordem estável
     * (`blockNumber ASC, logIndex ASC, id ASC`) entre chamadas sucessivas — condição necessária pro
     * loop "para quando não progride" do `RedecodeRunner` não pular/duplicar linhas entre lotes.
     */
    fun findPendingCandidates(filter: RedecodeFilter, limit: Int): List<BlockchainEvent>

    /** Total de candidatos pendentes pro [filter] — consulta ao vivo, usada por `RedecodeOrchestrator.status()`. */
    fun countPending(filter: RedecodeFilter): Long

    /**
     * `topic0`s distintos já ingeridos para este contrato (dedup) — fonte primária de "quais eventos
     * este contrato emite" pra detecção automática de interface (E6): a ingestão é da rede inteira
     * (DEC-001), então já observamos os logs de qualquer contrato, cadastrado ou não. Conjunto vazio
     * se o poller ainda não alcançou esse endereço.
     */
    fun findDistinctTopic0s(contractAddress: String): Set<String>
}
