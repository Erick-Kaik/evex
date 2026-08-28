package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface MonitoredContractJpaRepository : JpaRepository<MonitoredContractEntity, UUID> {
    fun findByAddress(address: String): MonitoredContractEntity?

    fun findByNetwork(network: Network, pageable: Pageable): Page<MonitoredContractEntity>
}
