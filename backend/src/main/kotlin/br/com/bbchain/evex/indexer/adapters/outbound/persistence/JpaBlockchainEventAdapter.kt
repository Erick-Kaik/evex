package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.event.EventSearchFilter
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component

/**
 * Implementação JPA/Postgres de [BlockchainEventPort]. `search` monta uma `Specification` dinâmica
 * combinando só os filtros presentes (mesmo padrão do `EventQueryService` do código base, citado em
 * `backend/CLAUDE.md`) — filtro de data (`fromDate`/`toDate`) aplica sobre `block_timestamp` (quando o
 * evento aconteceu on-chain, não quando foi ingerido). `findPendingCandidates`/`countPending` (E5)
 * seguem o mesmo padrão de `Specification` dinâmica, mas sempre com `decoded=false` fixo.
 *
 * `saveAll` (linhas novas, `INSERT`) e `updateAll` (linhas já existentes vindas de
 * `findPendingCandidates`, `UPDATE`) usam o mesmo `toEntity(isNew: Boolean)` — só muda a flag
 * `Persistable.isNew()` da entidade (achado da E5: `saveAll` chamado em linhas já persistidas tentaria
 * `INSERT` sobre PK existente).
 *
 * `rawTopics`/`decodedParameters` são serializados/desserializados manualmente via um `ObjectMapper`
 * Jackson 2 **local** (não injetado — DEC-020: o bean `ObjectMapper` auto-configurado pelo Spring Boot
 * 4.1 é Jackson 3, `tools.jackson`, incompatível com o resto do projeto).
 */
@Component
class JpaBlockchainEventAdapter(
    private val repository: BlockchainEventJpaRepository,
) : BlockchainEventPort {

    private val objectMapper = ObjectMapper()

    override fun existsByTxHashAndLogIndex(txHash: String, logIndex: Long): Boolean =
        repository.existsByTxHashAndLogIndex(txHash, logIndex)

    override fun saveAll(events: List<BlockchainEvent>): List<BlockchainEvent> {
        val entities = events.map { it.toEntity(isNew = true) }
        return repository.saveAll(entities).map { it.toDomain() }
    }

    override fun updateAll(events: List<BlockchainEvent>): List<BlockchainEvent> {
        val entities = events.map { it.toEntity(isNew = false) }
        return repository.saveAll(entities).map { it.toDomain() }
    }

    override fun findPendingCandidates(filter: RedecodeFilter, limit: Int): List<BlockchainEvent> {
        val pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "blockNumber", "logIndex", "id"))
        val specification = buildPendingSpecification(filter)
        return repository.findAll(specification, pageable).content.map { it.toDomain() }
    }

    override fun countPending(filter: RedecodeFilter): Long =
        repository.count(buildPendingSpecification(filter))

    override fun findDistinctTopic0s(contractAddress: String): Set<String> =
        repository.findDistinctTopic0sByContractAddress(contractAddress.lowercase())

    private fun buildPendingSpecification(filter: RedecodeFilter): Specification<BlockchainEventEntity> =
        Specification.allOf(
            listOfNotNull(
                Specification<BlockchainEventEntity> { root, _, cb -> cb.isFalse(root.get("decoded")) },
                filter.contractAddress?.let { address ->
                    Specification<BlockchainEventEntity> { root, _, cb -> cb.equal(root.get<String>("contractAddress"), address.lowercase()) }
                },
                filter.topic0?.let { topic0 ->
                    Specification<BlockchainEventEntity> { root, _, cb -> cb.equal(root.get<String>("topic0"), topic0) }
                },
                filter.fromBlock?.let { from ->
                    Specification<BlockchainEventEntity> { root, _, cb -> cb.greaterThanOrEqualTo(root.get("blockNumber"), from) }
                },
                filter.toBlock?.let { to ->
                    Specification<BlockchainEventEntity> { root, _, cb -> cb.lessThanOrEqualTo(root.get("blockNumber"), to) }
                },
            ),
        )

    override fun search(filter: EventSearchFilter, page: Int, size: Int): PageResult<BlockchainEvent> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "blockNumber", "logIndex"))
        val specification = buildSpecification(filter)
        val result = repository.findAll(specification, pageable)
        return PageResult(
            content = result.content.map { it.toDomain() },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    private fun buildSpecification(filter: EventSearchFilter): Specification<BlockchainEventEntity> =
        Specification.allOf(
            listOfNotNull(
                filter.contractAddress?.let { address ->
                    Specification<BlockchainEventEntity> { root, _, cb -> cb.equal(root.get<String>("contractAddress"), address.lowercase()) }
                },
                filter.eventName?.let { name ->
                    Specification<BlockchainEventEntity> { root, _, cb -> cb.equal(root.get<String>("eventName"), name) }
                },
                filter.fromBlock?.let { from ->
                    Specification<BlockchainEventEntity> { root, _, cb -> cb.greaterThanOrEqualTo(root.get("blockNumber"), from) }
                },
                filter.toBlock?.let { to ->
                    Specification<BlockchainEventEntity> { root, _, cb -> cb.lessThanOrEqualTo(root.get("blockNumber"), to) }
                },
                filter.fromDate?.let { from ->
                    Specification<BlockchainEventEntity> { root, _, cb -> cb.greaterThanOrEqualTo(root.get("blockTimestamp"), from) }
                },
                filter.toDate?.let { to ->
                    Specification<BlockchainEventEntity> { root, _, cb -> cb.lessThanOrEqualTo(root.get("blockTimestamp"), to) }
                },
            ),
        )

    private fun BlockchainEvent.toEntity(isNew: Boolean) = BlockchainEventEntity(
        id = id,
        contractAddress = contractAddress,
        network = network,
        blockNumber = blockNumber,
        blockHash = blockHash,
        txHash = txHash,
        logIndex = logIndex,
        topic0 = topic0,
        rawTopics = objectMapper.writeValueAsString(rawTopics),
        rawData = rawData,
        decoded = decoded,
        eventName = eventName,
        decodedParameters = decodedParameters?.let { objectMapper.writeValueAsString(it) },
        blockTimestamp = blockTimestamp,
        createdAt = createdAt,
        new = isNew,
    )

    private fun BlockchainEventEntity.toDomain() = BlockchainEvent(
        id = getId(),
        contractAddress = contractAddress,
        network = network,
        blockNumber = blockNumber,
        blockHash = blockHash,
        txHash = txHash,
        logIndex = logIndex,
        topic0 = topic0,
        rawTopics = objectMapper.readValue(rawTopics, object : TypeReference<List<String>>() {}),
        rawData = rawData,
        decoded = decoded,
        eventName = eventName,
        decodedParameters = decodedParameters?.let { objectMapper.readValue(it, object : TypeReference<Map<String, Any?>>() {}) },
        blockTimestamp = blockTimestamp,
        createdAt = createdAt,
    )
}
