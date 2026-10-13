package br.com.bbchain.evex.indexer.domain.event

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import java.util.UUID

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
     * Página de eventos **já decodificados** (`decoded=true`) para o backfill de saldo sob demanda
     * (E14, DEC-066): mesmo `(contractAddress, network, eventName)` da config, `blockNumber <=
     * [maxBlock]` (a marca d'água), e depois do cursor de progresso `([afterBlock], [afterLogIndex])`
     * — predicado de tupla `(blockNumber > afterBlock) OR (blockNumber = afterBlock AND logIndex >
     * afterLogIndex)`. Ordem `blockNumber ASC, logIndex ASC` (estável entre lotes sucessivos — o
     * cursor persistido depende disso pra não pular/duplicar linha). Cursor inicial `-1`/`-1` pega
     * tudo desde o começo.
     */
    fun findDecodedForBackfill(
        contractAddress: String,
        network: Network,
        eventName: String,
        afterBlock: Long,
        afterLogIndex: Long,
        maxBlock: Long,
        limit: Int,
    ): List<BlockchainEvent>

    /** Total ainda por reprojetar pro mesmo predicado de [findDecodedForBackfill] — consulta ao vivo, usada por `BalanceBackfillOrchestrator.globalStatus()`. */
    fun countDecodedForBackfill(
        contractAddress: String,
        network: Network,
        eventName: String,
        afterBlock: Long,
        afterLogIndex: Long,
        maxBlock: Long,
    ): Long

    /**
     * `topic0`s distintos já ingeridos para este contrato (dedup) — fonte primária de "quais eventos
     * este contrato emite" pra detecção automática de interface (E6): a ingestão é da rede inteira
     * (DEC-001), então já observamos os logs de qualquer contrato, cadastrado ou não. Conjunto vazio
     * se o poller ainda não alcançou esse endereço.
     */
    fun findDistinctTopic0s(contractAddress: String): Set<String>

    /** Evento por id (qualquer estado, `decoded` ou não) — preview/reenvio. */
    fun findById(id: UUID): BlockchainEvent?

    /** Eventos pelos [ids] existentes (ids inexistentes ficam de fora; ordem não garantida). */
    fun findAllByIds(ids: Collection<UUID>): List<BlockchainEvent>

    /**
     * Varredura por cursor, da mais recente para a mais antiga (`blockNumber DESC, logIndex DESC`), de
     * eventos `decoded = true` que casam com o pré-filtro [filter]. Só traz eventos ESTRITAMENTE antes de
     * [after] (ausente = a partir do mais recente). Leitura pura — os `filters` do webhook são aplicados
     * em memória por quem chama (E33, matching-events).
     */
    fun scanDecoded(filter: EventScanFilter, after: EventCursor?, limit: Int): List<BlockchainEvent>

    /**
     * Quantos eventos `decoded = true` do pré-filtro [filter] têm `blockTimestamp` nulo — ignorados por
     * um filtro de data (vira aviso); a faixa de data de [filter] é desconsiderada aqui, só a de bloco vale.
     */
    fun countDecodedWithoutTimestamp(filter: EventScanFilter): Long
}
