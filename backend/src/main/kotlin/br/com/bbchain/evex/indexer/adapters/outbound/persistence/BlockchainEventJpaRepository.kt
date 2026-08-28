package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * `JpaSpecificationExecutor` para a `Specification` dinâmica de [JpaBlockchainEventAdapter.search]
 * (filtros opcionais combináveis — mesmo padrão do `EventQueryService` do código base, citado em
 * `backend/CLAUDE.md`).
 */
interface BlockchainEventJpaRepository : JpaRepository<BlockchainEventEntity, UUID>, JpaSpecificationExecutor<BlockchainEventEntity> {
    fun existsByTxHashAndLogIndex(txHash: String, logIndex: Long): Boolean

    /** `topic0`s distintos já ingeridos para um contrato (E6, detecção automática de interface). */
    @Query("SELECT DISTINCT e.topic0 FROM BlockchainEventEntity e WHERE e.contractAddress = :contractAddress AND e.topic0 IS NOT NULL")
    fun findDistinctTopic0sByContractAddress(@Param("contractAddress") contractAddress: String): Set<String>
}
