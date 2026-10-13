package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.event.EventCursor
import br.com.bbchain.evex.indexer.domain.event.EventScanFilter
import br.com.bbchain.evex.indexer.domain.event.EventSearchFilter
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.util.UUID
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

    override fun findById(id: UUID): BlockchainEvent? = repository.findById(id).orElse(null)?.toDomain()

    override fun findAllByIds(ids: Collection<UUID>): List<BlockchainEvent> =
        if (ids.isEmpty()) emptyList() else repository.findAllById(ids.toSet()).map { it.toDomain() }

    override fun scanDecoded(filter: EventScanFilter, after: EventCursor?, limit: Int): List<BlockchainEvent> {
        val pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "blockNumber", "logIndex"))
        val specification = Specification.allOf(
            listOfNotNull(
                scanBaseSpecification(filter),
                after?.let { c ->
                    // Estritamente antes do cursor, na ordem (blockNumber DESC, logIndex DESC).
                    Specification<BlockchainEventEntity> { root, _, cb ->
                        cb.or(
                            cb.lessThan(root.get("blockNumber"), c.blockNumber),
                            cb.and(
                                cb.equal(root.get<Long>("blockNumber"), c.blockNumber),
                                cb.lessThan(root.get("logIndex"), c.logIndex),
                            ),
                        )
                    }
                },
            ),
        )
        return repository.findAll(specification, pageable).content.map { it.toDomain() }
    }

    override fun countDecodedWithoutTimestamp(filter: EventScanFilter): Long =
        repository.count(
            Specification.allOf(
                scanBaseSpecification(filter.copy(fromDate = null, toDate = null)),
                Specification<BlockchainEventEntity> { root, _, cb -> cb.isNull(root.get<Any>("blockTimestamp")) },
            ),
        )

    /** Reaproveita [buildSpecification] (contrato/evento/bloco/data) e soma `decoded = true` + `network`. */
    private fun scanBaseSpecification(filter: EventScanFilter): Specification<BlockchainEventEntity> =
        Specification.allOf(
            buildSpecification(
                EventSearchFilter(
                    contractAddress = filter.contractAddress,
                    eventName = filter.eventName,
                    fromBlock = filter.fromBlock,
                    toBlock = filter.toBlock,
                    fromDate = filter.fromDate,
                    toDate = filter.toDate,
                ),
            ),
            Specification<BlockchainEventEntity> { root, _, cb -> cb.isTrue(root.get("decoded")) },
            Specification<BlockchainEventEntity> { root, _, cb -> cb.equal(root.get<Network>("network"), filter.network) },
        )

    override fun findPendingCandidates(filter: RedecodeFilter, limit: Int): List<BlockchainEvent> {
        val pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "blockNumber", "logIndex", "id"))
        val specification = buildPendingSpecification(filter)
        return repository.findAll(specification, pageable).content.map { it.toDomain() }
    }

    override fun countPending(filter: RedecodeFilter): Long =
        repository.count(buildPendingSpecification(filter))

    override fun findDistinctTopic0s(contractAddress: String): Set<String> =
        repository.findDistinctTopic0sByContractAddress(contractAddress.lowercase())

    override fun findDecodedForBackfill(
        contractAddress: String,
        network: Network,
        eventName: String,
        afterBlock: Long,
        afterLogIndex: Long,
        maxBlock: Long,
        limit: Int,
    ): List<BlockchainEvent> {
        val pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "blockNumber", "logIndex"))
        val specification = buildBackfillSpecification(contractAddress, network, eventName, afterBlock, afterLogIndex, maxBlock)
        return repository.findAll(specification, pageable).content.map { it.toDomain() }
    }

    override fun countDecodedForBackfill(
        contractAddress: String,
        network: Network,
        eventName: String,
        afterBlock: Long,
        afterLogIndex: Long,
        maxBlock: Long,
    ): Long = repository.count(buildBackfillSpecification(contractAddress, network, eventName, afterBlock, afterLogIndex, maxBlock))

    /**
     * `decoded=true` + `(contractAddress, network, eventName)` da config + `blockNumber <= maxBlock`
     * (marca d'água) + tupla de cursor `(blockNumber > afterBlock) OR (blockNumber = afterBlock AND
     * logIndex > afterLogIndex)` (E14, DEC-066).
     */
    private fun buildBackfillSpecification(
        contractAddress: String,
        network: Network,
        eventName: String,
        afterBlock: Long,
        afterLogIndex: Long,
        maxBlock: Long,
    ): Specification<BlockchainEventEntity> =
        Specification.allOf(
            Specification<BlockchainEventEntity> { root, _, cb -> cb.isTrue(root.get("decoded")) },
            Specification<BlockchainEventEntity> { root, _, cb -> cb.equal(root.get<String>("contractAddress"), contractAddress.lowercase()) },
            Specification<BlockchainEventEntity> { root, _, cb -> cb.equal(root.get<Network>("network"), network) },
            Specification<BlockchainEventEntity> { root, _, cb -> cb.equal(root.get<String>("eventName"), eventName) },
            Specification<BlockchainEventEntity> { root, _, cb -> cb.lessThanOrEqualTo(root.get("blockNumber"), maxBlock) },
            Specification<BlockchainEventEntity> { root, _, cb ->
                cb.or(
                    cb.greaterThan(root.get("blockNumber"), afterBlock),
                    cb.and(
                        cb.equal(root.get<Long>("blockNumber"), afterBlock),
                        cb.greaterThan(root.get("logIndex"), afterLogIndex),
                    ),
                )
            },
        )

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
        // `walletAddress` sai do `Specification.allOf(...)` dinâmico de propósito (DEC-080) — caminho
        // de query nativo dedicado em [BlockchainEventJpaRepository.searchByWalletAddress]. Quando
        // `null`, comportamento idêntico ao pré-existente (nenhuma regressão).
        val result = if (filter.walletAddress != null) {
            // `PageRequest` **sem** `Sort` de propósito: o Spring Data JPA, pra query nativa com
            // `Pageable`, concatena o `Sort` recebido crú ao final do SQL usando o nome do *atributo*
            // Kotlin (`blockNumber`/`logIndex`), não o nome da *coluna* (`block_number`/`log_index`) —
            // gera `ORDER BY ... , blockNumber desc, logIndex desc` e quebra com "column blocknumber
            // does not exist" (achado empírico rodando contra Testcontainers). O `ORDER BY` já vem
            // fixo no SQL da query (mesma ordenação `blockNumber DESC, logIndex DESC` do `Specification`
            // abaixo) — só precisamos do `Pageable` pra `LIMIT`/`OFFSET`.
            repository.searchByWalletAddress(
                walletAddress = filter.walletAddress.lowercase(),
                contractAddress = filter.contractAddress?.lowercase(),
                eventName = filter.eventName,
                fromBlock = filter.fromBlock,
                toBlock = filter.toBlock,
                fromDate = filter.fromDate,
                toDate = filter.toDate,
                pageable = PageRequest.of(page, size),
            )
        } else {
            val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "blockNumber", "logIndex"))
            repository.findAll(buildSpecification(filter), pageable)
        }
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
