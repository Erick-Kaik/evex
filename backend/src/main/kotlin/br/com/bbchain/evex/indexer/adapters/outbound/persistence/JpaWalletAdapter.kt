package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.wallet.Wallet
import br.com.bbchain.evex.indexer.domain.wallet.WalletPort
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

/**
 * Implementação JPA/Postgres de [WalletPort]. Espelha [JpaMonitoredContractAdapter] 1:1 — decide
 * insert vs. update olhando se já existe uma linha com o mesmo `id` (ver [WalletEntity] pro porquê do
 * `Persistable<UUID>`).
 */
@Component
class JpaWalletAdapter(
    private val repository: WalletJpaRepository,
) : WalletPort {

    override fun save(wallet: Wallet): Wallet {
        val alreadyPersisted = repository.existsById(wallet.id)
        val entity = wallet.toEntity(isNew = !alreadyPersisted)
        return repository.save(entity).toDomain()
    }

    override fun findByAddress(address: String): Wallet? =
        repository.findByAddress(address)?.toDomain()

    override fun findAll(network: Network?, page: Int, size: Int): PageResult<Wallet> {
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

    override fun findAllAddresses(network: Network?): Set<String> =
        repository.findAllAddressesBy(network).toSet()

    private fun Wallet.toEntity(isNew: Boolean) = WalletEntity(
        id = id,
        address = address,
        label = label,
        description = description,
        network = network,
        status = status,
        looksLikeContract = looksLikeContract,
        looksLikeContractAcknowledged = looksLikeContractAcknowledged,
        new = isNew,
    )

    private fun WalletEntity.toDomain() = Wallet(
        id = getId(),
        address = address,
        label = label,
        description = description,
        network = network,
        status = status,
        looksLikeContract = looksLikeContract,
        looksLikeContractAcknowledged = looksLikeContractAcknowledged,
    )
}
