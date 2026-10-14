package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Implementação JPA/Postgres de [ContractAbiPort] — mesmo padrão de [JpaMonitoredContractAdapter]. */
@Component
class JpaContractAbiAdapter(
    private val repository: ContractAbiJpaRepository,
) : ContractAbiPort {

    override fun save(abi: ContractAbi): ContractAbi {
        val alreadyPersisted = repository.existsById(abi.id)
        val entity = abi.toEntity(isNew = !alreadyPersisted)
        return repository.save(entity).toDomain()
    }

    override fun findAllByContractAddress(address: String): List<ContractAbi> =
        repository.findByContractAddressOrderByValidFromBlockAsc(address).map { it.toDomain() }

    override fun findCurrentOpenVersion(address: String): ContractAbi? =
        repository.findFirstByContractAddressAndValidToBlockIsNull(address)?.toDomain()

    override fun findAll(): List<ContractAbi> = repository.findAll().map { it.toDomain() }

    @Transactional
    override fun deleteLegacyBlockscoutVersions(address: String): Int =
        repository.deleteLegacyBySource(address, AbiSource.BLOCKSCOUT)

    private fun ContractAbi.toEntity(isNew: Boolean) = ContractAbiEntity(
        id = id,
        contractAddress = contractAddress,
        network = network,
        source = source,
        storageType = storageType,
        abiContent = storageLocation.abiContent,
        storagePath = storageLocation.storagePath,
        validFromBlock = validFromBlock,
        validToBlock = validToBlock,
        createdAt = createdAt,
        implementationAddress = implementationAddress,
        new = isNew,
    )

    private fun ContractAbiEntity.toDomain() = ContractAbi(
        id = getId(),
        contractAddress = contractAddress,
        network = network,
        source = source,
        storageType = storageType,
        storageLocation = StorageLocation(abiContent = abiContent, storagePath = storagePath),
        validFromBlock = validFromBlock,
        validToBlock = validToBlock,
        createdAt = createdAt,
        implementationAddress = implementationAddress,
    )
}
