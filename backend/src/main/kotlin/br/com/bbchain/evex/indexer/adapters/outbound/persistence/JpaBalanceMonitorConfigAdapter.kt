package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigPort
import br.com.bbchain.evex.indexer.domain.common.PageResult
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

/**
 * Implementação JPA/Postgres de [BalanceMonitorConfigPort]. Decide insert vs. update olhando se já
 * existe uma linha com o mesmo `id` (mesmo padrão de [JpaMonitoredContractAdapter]).
 */
@Component
class JpaBalanceMonitorConfigAdapter(
    private val repository: BalanceMonitorConfigJpaRepository,
) : BalanceMonitorConfigPort {

    override fun save(config: BalanceMonitorConfig): BalanceMonitorConfig {
        val alreadyPersisted = repository.existsById(config.id)
        val entity = config.toEntity(isNew = !alreadyPersisted)
        return repository.save(entity).toDomain()
    }

    override fun findById(id: UUID): BalanceMonitorConfig? = repository.findById(id).map { it.toDomain() }.orElse(null)

    override fun findActive(contractAddress: String, eventName: String): List<BalanceMonitorConfig> =
        repository.findByContractAddressAndEventNameAndActiveTrue(contractAddress.lowercase(), eventName).map { it.toDomain() }

    override fun findAll(page: Int, size: Int): PageResult<BalanceMonitorConfig> {
        val pageable = PageRequest.of(page, size, Sort.by("createdAt"))
        val result = repository.findAll(pageable)
        return PageResult(
            content = result.content.map { it.toDomain() },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    private fun BalanceMonitorConfig.toEntity(isNew: Boolean) = BalanceMonitorConfigEntity(
        id = id,
        contractAddress = contractAddress,
        network = network,
        eventName = eventName,
        behavior = behavior,
        walletParam = walletParam,
        fromParam = fromParam,
        toParam = toParam,
        valueParam = valueParam,
        tokenIdParam = tokenIdParam,
        active = active,
        createdAt = createdAt,
        new = isNew,
    )

    private fun BalanceMonitorConfigEntity.toDomain() = BalanceMonitorConfig(
        id = getId(),
        contractAddress = contractAddress,
        network = network,
        eventName = eventName,
        behavior = behavior,
        walletParam = walletParam,
        fromParam = fromParam,
        toParam = toParam,
        valueParam = valueParam,
        tokenIdParam = tokenIdParam,
        active = active,
        createdAt = createdAt,
    )
}
