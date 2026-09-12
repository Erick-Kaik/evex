package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

/**
 * Implementação JPA/Postgres de [MonitoredContractPort]. Decide insert vs. update olhando se já existe
 * uma linha com o mesmo `id` (ver [MonitoredContractEntity] para o porquê do `Persistable<UUID>`).
 */
@Component
class JpaMonitoredContractAdapter(
    private val repository: MonitoredContractJpaRepository,
) : MonitoredContractPort {

    override fun save(contract: MonitoredContract): MonitoredContract {
        val alreadyPersisted = repository.existsById(contract.id)
        val entity = contract.toEntity(isNew = !alreadyPersisted)
        return repository.save(entity).toDomain()
    }

    override fun findByAddress(address: String): MonitoredContract? =
        repository.findByAddress(address)?.toDomain()

    override fun findAll(network: Network?, page: Int, size: Int): PageResult<MonitoredContract> {
        val pageable = PageRequest.of(page, size, Sort.by("address"))
        val result = if (network != null) {
            repository.findByNetwork(network, pageable)
        } else {
            repository.findAll(pageable)
        }
        return PageResult(
            content = result.content.map { it.toDomain() },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    override fun findAllByLineageId(lineageId: UUID): List<MonitoredContract> =
        repository.findByLineageIdOrderByCreatedAt(lineageId).map { it.toDomain() }

    private fun MonitoredContract.toEntity(isNew: Boolean) = MonitoredContractEntity(
        id = id,
        address = address,
        name = name,
        description = description,
        version = version,
        network = network,
        status = status,
        lineageId = lineageId,
        createdAt = createdAt,
        new = isNew,
    )

    private fun MonitoredContractEntity.toDomain() = MonitoredContract(
        id = getId(),
        address = address,
        name = name,
        description = description,
        version = version,
        network = network,
        status = status,
        lineageId = lineageId,
        createdAt = createdAt,
    )
}
