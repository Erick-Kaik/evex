package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ContractAbiJpaRepository : JpaRepository<ContractAbiEntity, UUID> {
    fun findByContractAddressOrderByValidFromBlockAsc(contractAddress: String): List<ContractAbiEntity>

    fun findFirstByContractAddressAndValidToBlockIsNull(contractAddress: String): ContractAbiEntity?

    /** DEC-105/D4: só `source` informado (BLOCKSCOUT) com `implementation_address` nulo. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ContractAbiEntity e where e.contractAddress = :address and e.source = :source and e.implementationAddress is null")
    fun deleteLegacyBySource(@Param("address") address: String, @Param("source") source: AbiSource): Int
}
