package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WebhookDeliveryAttemptJpaRepository : JpaRepository<WebhookDeliveryAttemptEntity, UUID> {
    fun findByDeliveryIdOrderByAttemptNumberAsc(deliveryId: UUID): List<WebhookDeliveryAttemptEntity>
}
