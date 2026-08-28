package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WebhookConfigJpaRepository : JpaRepository<WebhookConfigEntity, UUID> {
    fun findByContractAddressAndEventNameAndActiveTrue(contractAddress: String, eventName: String): List<WebhookConfigEntity>
}
