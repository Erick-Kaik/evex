package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface ContractAbiJpaRepository : JpaRepository<ContractAbiEntity, UUID> {
    fun findByContractAddressOrderByValidFromBlockAsc(contractAddress: String): List<ContractAbiEntity>

    fun findFirstByContractAddressAndValidToBlockIsNull(contractAddress: String): ContractAbiEntity?
}
