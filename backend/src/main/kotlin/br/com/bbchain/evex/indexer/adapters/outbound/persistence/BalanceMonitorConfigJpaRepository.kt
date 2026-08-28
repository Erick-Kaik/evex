package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface BalanceMonitorConfigJpaRepository : JpaRepository<BalanceMonitorConfigEntity, UUID> {
    fun findByContractAddressAndEventNameAndActiveTrue(contractAddress: String, eventName: String): List<BalanceMonitorConfigEntity>
}
